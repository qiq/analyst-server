package models;

import com.conveyal.analyst.server.utils.DataStore;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import org.opentripplanner.analyst.scenario.Modification;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Scenarios are lightweight modifications on top of bundles. For example, you might have a single San Francisco
 * graph with transit data for the entire Bay Area. This is a several-GB object. Paul might be analyzing the
 * effects of a new bus line, which is represented as a scenario. Leila is analyzing the effects of a construction
 * project that will temporarily suspend streetcar service. Sarah is analyzing the effects of a new Transbay tube.
 * All use the same base graph but with different lightweight lists of modifications.
 * 
 * This is TransportScenario not plain Scenario to avoid clashes with old data directories.
 */
public class TransportScenario implements Serializable {
	public static final long serialVersionUID = 1L;
	
	// called transport_scenario not scenario because scenario is what bundles used to be called.
	// _data is to avoid clashes with transport_scenario.db from 0.5.x which is this class but with a different class name
	private static final DataStore<TransportScenario> scenarioStore = new DataStore<TransportScenario>("transport_scenario_data", true);
	
	/** The bundle that this scenario is based on */
	public String bundleId;
	
	/** The project this scenario is associated with */
	public String projectId;
	
	/** The ID of this scenario */
	public String id;
	
	/** The name of this scenario */
	public String name;
	
	/** The description of this scenario */
	public String description;
	
	/** The project that this scenario is associated with */
	
	/** A list of banned routes */
	public List<Bundle.RouteSummary> bannedRoutes;

	/** A list of other modifications to make to the graph, for advanced users */
	public List<Modification> modifications;
	
	// TODO additional types of modifications
	
	/** Create and save a new scenario based on the named bundle */
	public static TransportScenario create (Bundle bundle) {
		TransportScenario s = new TransportScenario();
		s.bundleId = bundle.id;
		s.projectId = bundle.projectId;
		s.generateId();
		s.save();
		return s;
	}
	
	public void save () {
		scenarioStore.save(id, this);
	}
	
	public static TransportScenario getScenario (String id) {
		return scenarioStore.getById(id);
	}
	
	public static Collection<TransportScenario> getAll () {
		return scenarioStore.getAll();
	}
	
	public static Collection<TransportScenario> getByProject (final String projectId) {
		return Collections2.filter(getAll(), new Predicate<TransportScenario>() {

			@Override
			public boolean apply(TransportScenario arg0) {
				return projectId.equals(arg0.projectId);
			}
		});
	}

	public void generateId() {
		this.id = UUID.randomUUID().toString();
	}

	/** has this scenario been saved previously? */
	public boolean exists() {
		return scenarioStore.contains(id);
	}

	public void delete() {
		scenarioStore.delete(id);
	}
}
