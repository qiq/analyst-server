package com.conveyal.analyst.server.controllers;

import com.conveyal.analyst.server.utils.*;
import models.Project;
import models.Query;
import models.Shapefile;
import models.User;
import org.opentripplanner.analyst.cluster.ResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static spark.Spark.halt;

/**
 * Multipoint query controller
 */
public class QueryController extends Controller {
    private static final Logger LOG = LoggerFactory.getLogger(QueryController.class);

    public static Object getQuery(Request req, Response res) {
        // auth is not handled controller-wide as there is more nuance here,
        // this particular method requires auth
        Authentication.authenticated(req, res);

        String projectId = req.queryParams("projectId");

        Collection<Query> result = null;

        if(req.params("id") != null) {
            Query q = Query.getQuery(req.params("id"));
            if (q != null && currentUser(req).hasReadPermission(q.projectId))
                return q;
            else
                halt(NOT_FOUND, "Could not find query");
        }
        else if (projectId != null) {
            if (Project.getProject(projectId) == null || !currentUser(req).hasReadPermission(projectId))
                halt(NOT_FOUND, "Could not find project");

            result = Query.getQueriesByProject(projectId);
        }
        else {
            // all queries for the user
            Set<String> userProjects = currentUser(req).projectPermissions.stream()
                    .filter(pp -> pp.read)
                    .map(pp -> pp.projectId)
                    .collect(Collectors.toSet());

            result = Query.getQueries().stream().filter(q -> userProjects.contains(q.projectId))
                    .collect(Collectors.toList());
        }

        if (!Boolean.parseBoolean(req.queryParams("archived"))) {
            result = result.stream().filter(q -> !q.archived).collect(Collectors.toList());
        }

        return result;
    }

    public static Query createQuery(Request req, Response res) {
        Authentication.authenticated(req, res);

        Query q;
        try {
            q = JsonUtil.getObjectMapper().readValue(req.body(), Query.class);
        } catch (Exception e) {
            LOG.warn("error processing query creation json", e);
            halt(BAD_REQUEST, "error processing JSON");
            return null;
        }

        User u = currentUser(req);

        if (q.projectId == null || !u.hasWritePermission(q.projectId))
            halt(UNAUTHORIZED, "You do not have write access to this project");

        // ensure they have quota available
        Shapefile shapefile = Shapefile.getShapefile(q.originShapefileId);
        if (u.getQuota() < shapefile.getFeatureCount()) {
            LOG.warn("User {} was unable to add query of size {} because their quota has been met", u.username, shapefile.getFeatureCount());
            halt(FORBIDDEN, INSUFFICIENT_QUOTA);
        }

        q.save();

        q.run();

        QuotaLedger.LedgerEntry entry = new QuotaLedger.LedgerEntry();
        entry.delta = -shapefile.getFeatureCount();
        entry.userId = u.username;
        entry.groupId = u.groupName;
        entry.query = q.id;
        entry.queryName = q.name;
        entry.reason = QuotaLedger.LedgerReason.QUERY_CREATED;
        u.addLedgerEntry(entry);

        return q;

    }

    public static Query updateQuery(Request req, Response res) {
        Authentication.authenticated(req, res);

        Query q;

        try {
            q = JsonUtil.getObjectMapper().readValue(req.body(), Query.class);
        } catch (Exception e) {
            LOG.warn("error processing query update JSON", e);
            halt(BAD_REQUEST, e.getMessage());
            return null;
        }

        if (q.id == null)
            halt(BAD_REQUEST, "please specify an ID");

        Query ex = Query.getQuery(q.id);
        User u = currentUser(req);

        if (ex == null || !u.hasWritePermission(q.projectId) || !u.hasWritePermission(ex.projectId))
            halt(NOT_FOUND, "Query not found or you do not have permission to access it");

        q.save();

        return q;
    }

    public static Query deleteQuery (Request req, Response res) throws IOException {
        Authentication.authenticated(req, res);

        String id = req.params("id");
        if (id == null || id.isEmpty())
            halt(BAD_REQUEST, "ID must not be null");

        Query q = Query.getQuery(id);

        User u = currentUser(req);

        if (q == null || !u.hasWritePermission(q.projectId))
            halt(NOT_FOUND, "Query not found or you do not have permission to delete it");

        QueueManager.getManager().cancelJob(q.id);
        q.delete();

        // any points they didn't use go back on their quota
        q.refundPartial(u);

        return q;
    }

    /** Get the classes/bins for a particular query */
    public static List<Bin> queryBins(Request req, Response res) {
        Authentication.authenticatedOrCors(req, res);

        String queryId = req.params("id");
        int timeLimit = Integer.parseInt(req.queryParams("timeLimit"));
        String weightByShapefile = req.queryParams("weightByShapefile");
        String weightByAttribute = req.queryParams("weightByAttribute");
        String groupBy = req.queryParams("groupBy");
        ResultEnvelope.Which which = ResultEnvelope.Which.valueOf(req.queryParams("which"));
        String attributeName = req.queryParams("attributeName");
        String compareTo = req.params("compareTo");

        Query query = Query.getQuery(queryId);

        User u = currentUser(req);

        // if u is null then auth is turned off for this api endpoint
        if (query == null || (u != null && !u.hasReadPermission(query.projectId)))
            halt(NOT_FOUND, "Query not found or you do not have access to it");

        Query otherQuery = null;

        if (compareTo != null) {
            otherQuery = Query.getQuery(compareTo);

            if (otherQuery == null || (u != null && !u.hasReadPermission(otherQuery.projectId)))
                halt(NOT_FOUND, "Query not found or you do not have access to it");

            if (otherQuery == null) {
                halt(NOT_FOUND, "non-existent comparison query");
            }
        }

        String queryKey = queryId + "_" + timeLimit + "_" + which + "_" + attributeName;

        QueryResults qr = null;

        synchronized (QueryResults.queryResultsCache) {
            if (!QueryResults.queryResultsCache.containsKey(queryKey)) {
                qr = new QueryResults(query, timeLimit, which, attributeName);
                QueryResults.queryResultsCache.put(queryKey, qr);
            } else
                qr = QueryResults.queryResultsCache.get(queryKey);
        }

        if (otherQuery != null) {
            QueryResults otherQr = null;

            queryKey = compareTo + "_" + timeLimit + "_" + which + "_" + attributeName;
            if (!QueryResults.queryResultsCache.containsKey(queryKey)) {
                otherQr = new QueryResults(otherQuery, timeLimit, which, attributeName);
                QueryResults.queryResultsCache.put(queryKey, otherQr);
            } else {
                otherQr = QueryResults.queryResultsCache.get(queryKey);
            }

            qr = qr.subtract(otherQr);
        }

        if (weightByShapefile == null) {
            return qr.classifier.getBins();
        } else {
            Shapefile aggregateTo = Shapefile.getShapefile(groupBy);

            Shapefile weightBy = Shapefile.getShapefile(weightByShapefile);
            return qr.aggregate(aggregateTo, weightBy, weightByAttribute).classifier.getBins();

        }
    }
}
