package models;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Serializable;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import jobs.ProcessTransitScenarioJob;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.geotools.geometry.Envelope2D;
import org.joda.time.DateTimeZone;
import org.mapdb.Bind;
import org.mapdb.Fun;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.otpac.ClusterGraphService;
import com.conveyal.otpac.PointSetDatastore;
import com.vividsolutions.jts.index.strtree.STRtree;

import org.opentripplanner.routing.graph.Graph;

import play.Logger;
import play.Play;
import play.libs.Akka;
import play.libs.F.Function0;
import play.libs.F.Promise;
import scala.concurrent.ExecutionContext;
import scala.concurrent.duration.Duration;
import utils.Bounds;
import utils.DataStore;
import utils.HashUtils;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.io.ByteStreams;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;

import controllers.Api;
import controllers.Application;


@JsonIgnoreProperties(ignoreUnknown = true)
public class Scenario implements Serializable {

	private static final long serialVersionUID = 1L;
	
	private static ClusterGraphService clusterGraphService;
	
	static {
		String s3credentials = Play.application().configuration().getString("cluster.s3credentials");
		String bucket = Play.application().configuration().getString("cluster.graphs-bucket");
		boolean workOffline = Play.application().configuration().getBoolean("cluster.work-offline");
		clusterGraphService = new ClusterGraphService(s3credentials, workOffline, bucket);
	}
	

	static DataStore<Scenario> scenarioData = new DataStore<Scenario>("scenario", true);

	public String id;
	public String projectId;
	public String name;
	public String description;
	
	public DateTimeZone timeZone;
	
	public Boolean processingGtfs = false;
	public Boolean processingOsm = false;
	public Boolean failed = false;
	
	public Bounds bounds;
	
	/** spatial index of transit layer. */
	private transient STRtree spIdx;
	
	/** TODO: should probably be in separate map in mapdb */
	@JsonIgnore
	public List<LineString> shapes = new ArrayList<LineString>();
	
	@JsonIgnore
	public STRtree getSpatialIndex () {
		if (spIdx == null) {
			buildSpatialIndexIfNeeded();
		}
		
		return spIdx;
	}
	
	/** build the spatial index */
	private synchronized void buildSpatialIndexIfNeeded () {
		if (spIdx != null)
			return;
		
		spIdx = new STRtree(Math.max(shapes.size(), 2));
		
		for (LineString geom : shapes) {
			spIdx.insert(geom.getEnvelopeInternal(), geom);
		}
	}
	
	public Scenario() {}
		
	public String getStatus() {
		
		if(processingGtfs)
			return "PROCESSSING_GTFS";
		else if(processingOsm) 
			return "PROCESSSING_OSM";
		else 
			return "BUILT";
		
	}
	
	static public Scenario create(final File gtfsFile, final String scenarioType, final String augmentScenarioId) throws IOException {
		
		final Scenario scenario = new Scenario();
		scenario.save();
		
		scenario.processGtfs(gtfsFile, scenarioType, augmentScenarioId);
		
		return scenario;
	}
	
	public List<String> getFiles() {
		
		ArrayList<String> files = new ArrayList<String>();
		
		for(File f : this.getScenarioDataPath().listFiles()) {
			files.add(f.getName());
		}
		
		return files;	
	}
	
	public void save() {
		
		// assign id at save
		if(id == null || id.isEmpty()) {
			
			Date d = new Date();
			id = HashUtils.hashString("sc_" + d.toString());
			
			Logger.info("created scenario s " + id);
		}
		
		scenarioData.save(id, this);
		
		Logger.info("saved scenario s " +id);
	}
	
	@JsonIgnore
	private static File getScenarioDir() {
		File scenarioPath = new File(Application.dataPath, "graphs");
		
		scenarioPath.mkdirs();
		
		return scenarioPath;
	}
	
	@JsonIgnore
	public File getScenarioDataPath() {
		
		File scenarioDataPath = new File(getScenarioDir(), id);
		
		scenarioDataPath.mkdirs();
		
		return scenarioDataPath;
	}
	
	public void delete() throws IOException {
		scenarioData.delete(id);
		
		FileUtils.deleteDirectory(getScenarioDataPath());
		
		Logger.info("delete scenario s" +id);
	}
	
	public void processGtfs(final File gtfsFile, final String scenarioType, final String augmentScenarioId) {
		ExecutionContext graphBuilderContext = Akka.system().dispatchers().lookup("contexts.graph-builder-analyst-context");
		
		final Scenario scenario = this;
		
		Akka.system().scheduler().scheduleOnce(
			        Duration.create(10, TimeUnit.MILLISECONDS),
			        new ProcessTransitScenarioJob(this, gtfsFile, scenarioType, augmentScenarioId),
			        graphBuilderContext
			);
	}
	
	public void writeToClusterCache () throws IOException {
		clusterGraphService.addGraphFile(getScenarioDataPath());
	}
	
	static public void writeAllToClusterCache () throws IOException {
		for (Scenario s : scenarioData.getAll()) {
			s.writeToClusterCache();
		}
	}

	static public Scenario getScenario(String id) {
		
		return scenarioData.getById(id);	
	}
	
	static public Collection<Scenario> getScenarios(String projectId) throws IOException {
		
		if(projectId == null)
			return scenarioData.getAll();
		
		else {
			
			Collection<Scenario> data = new ArrayList<Scenario>();
			
			for(Scenario sd : scenarioData.getAll()) {
				if(sd.projectId == null )
					sd.delete();
				else if(sd.projectId.equals(projectId))
					data.add(sd);
			}
		
			return data;
		}
		
	}
	
	@JsonIgnore
	public File getTempShapeDirPath() {
		
		File shapeDirPath = new File(getScenarioDataPath(), "tmp_" + id);
		
		shapeDirPath.mkdirs();
		
		return shapeDirPath;
	}
	
}
