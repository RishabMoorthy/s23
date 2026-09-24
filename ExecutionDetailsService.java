package org.framework.db.service;

import com.stubio.util.ExecutionMode;

import org.common.db.config.ConfigLoader;
import org.common.db.entity.VsCatalog;
import org.common.db.entity.VsExecutionMode;
import org.common.db.entity.VsLiveUrl;
import org.common.db.repository.VsCatalogRepository;
import org.common.db.repository.VsExecutionModeRepository;
import org.common.db.repository.VsLiveUrlRepository;

import org.framework.config.ExecutionModeType;
import org.framework.config.ServiceConfig;
import org.framework.db.repository.ExecutionModeRepository;
import org.framework.utils.ExecutionModeXmlUpdater;
import org.framework.utils.LiveUrlFormat;
import org.framework.utils.Logger;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Keeps a service's execution mode in step between the database and its .vs file.
 *
 * <p>The database wins. VS_EXECUTIONMODE / VS_LIVEURLS are edited from the portal, so their
 * values are pushed into the live ExecutionMode object and written back into the .vs file. The
 * .vs is the source in one case only: when no database row exists yet, the rows are created
 * from it.
 *
 * <p>Applying the mode to the parsed ExecutionMode object is what makes it take effect -
 * ServiceConfig delegates to the same VirtualServiceObject that ResponseResolver, BaseRoute and
 * LiveInvocation read through, so nothing on the request path needs to know about any of this.
 *
 * <p>Every query but the VSID allocation comes from the shared hibernate repositories as they
 * already stand; see {@link ExecutionModeRepository} for why nothing was added there.
 *
 * <p>BasePath takes no part in the live endpoint and is neither read nor written.
 */
public class ExecutionDetailsService {

    private static final String STUBSERVER_IP = "stubserver.ip";
    private static final String ACTIVE_YES = "Y";
    private static final String ACTIVE_NO = "N";

    private final VsCatalogRepository catalogRepository = new VsCatalogRepository();
    private final VsExecutionModeRepository executionModeRepository = new VsExecutionModeRepository();
    private final VsLiveUrlRepository liveUrlRepository = new VsLiveUrlRepository();
    private final ExecutionModeRepository vsidRepository = new ExecutionModeRepository();

    /**
     * Reconciles the database and the .vs file for one service.
     *
     * @throws ExecutionModeSyncException when stubserver.ip is not configured, or the mode is
     *                                    enabled and the service is not in READYAPI_VS_CATALOG -
     *                                    operator errors that must fail a deploy. A service with
     *                                    the mode switched off is not held to the catalog
     *                                    requirement; it is logged and left alone.
     */
    public void syncExecutionMode(ServiceConfig config) {

        String virtServer = resolveVirtServer();
        String serviceName = config.getServiceName();

        // An empty <ExecutionModeValue/> means the feature is switched off for this service:
        // it serves its configured responses and never calls a destination server. Nothing
        // needs resolving, so a missing catalog entry is only worth a warning here.
        boolean modeEnabled = config.getExecutionModeType() != ExecutionModeType.NONE;

        try {
            Optional<VsCatalog> found = catalogRepository.findByVsname(serviceName);

            if (found.isEmpty()) {

                if (modeEnabled) {
                    throw new ExecutionModeSyncException(
                            "Service '" + serviceName + "' is not in the catalog."
                                    + " Add it to READYAPI_VS_CATALOG, then deploy.");
                }

                // Nothing else to do, and nothing can be recorded without a MASTERID.
                Logger.getInstance().info("[ExecutionMode] " + serviceName
                        + ": execution mode is off and the service is not in READYAPI_VS_CATALOG;"
                        + " nothing to sync. Add a catalog entry if it should be switchable"
                        + " from the portal later.");
                return;
            }

            VsCatalog catalog = found.get();

            Optional<VsExecutionMode> existing = executionModeRepository
                    .findByMasterIdAndVirtServer(catalog.getMasterId(), virtServer);

            if (existing.isPresent()) {
                applyDatabaseValues(config, existing.get());
            } else {
                createRecordsFromVsFile(config, catalog, virtServer);
            }

        } catch (ExecutionModeSyncException e) {
            throw e;

        } catch (Exception e) {
            // The database being down must not stop a service from starting - it simply runs on
            // whatever the .vs file says.
            Logger.getInstance().info("[ExecutionMode] Could not reach the database for '"
                    + serviceName + "' (virtServer=" + virtServer + "). Continuing on .vs values. "
                    + e.getMessage());
        }
    }

    // ---- flow A: database record exists, database wins ----

    private void applyDatabaseValues(ServiceConfig config, VsExecutionMode record) throws Exception {

        String serviceName = config.getServiceName();
        String dbMode = trimToEmpty(record.getExecutionMode());
        ExecutionModeType type = ExecutionModeType.from(dbMode);

        if (dbMode.isEmpty()) {
            // No opinion recorded - the .vs value stands.
            Logger.getInstance().info("[ExecutionMode] " + serviceName
                    + ": database row carries no mode; keeping .vs values.");
            return;
        }

        // Only the two live modes have any use for a LiveURL. Reading one for Stand-In would
        // also make every deploy look like a change, because the file carries an empty
        // <LiveURLs/> for that mode.
        LiveUrlFormat.Parsed activeUrl = type.usesLiveUrls()
                ? activeHostOf(record.getVsid())
                : null;

        if (type.usesLiveUrls() && activeUrl == null) {
            Logger.getInstance().info("[ExecutionMode] " + serviceName
                    + ": mode is " + dbMode + " but no live URL is active in VS_LIVEURLS;"
                    + " applying the mode and keeping the .vs host and port.");
        }

        ExecutionMode model = config.getExecutionModeModel();

        boolean memoryChanged = false;
        if (model != null) {
            memoryChanged = applyToModel(model, dbMode, activeUrl);
        }

        ExecutionModeXmlUpdater.Result result = ExecutionModeXmlUpdater.apply(
                serviceName,
                config.getVirtualService() == null ? null : config.getVirtualService().getXmlPath(),
                dbMode,
                activeUrl);

        if (result.getXmlContent() != null) {
            config.setXmlFileContent(result.getXmlContent());
        }

        if (memoryChanged || result.isChanged()) {
            Logger.getInstance().info("[ExecutionMode] " + serviceName
                    + ": applied database values - mode=" + dbMode
                    + " (" + type + "), liveUrl="
                    + (activeUrl == null ? (type.usesLiveUrls() ? "(kept from .vs)" : "(not used by this mode)")
                                         : activeUrl.toString()));
        }
    }

    /**
     * The active live URL for a vsid.
     *
     * <p>The rows are fetched and the active one chosen here rather than in SQL, so that
     * ISACTIVE values like {@code 'y'} or {@code ' Y'} still count as active - the column is
     * VARCHAR(2) and is written by more than one component.
     */
    private LiveUrlFormat.Parsed activeHostOf(Long vsid) {

        for (VsLiveUrl url : liveUrlRepository.findByVsid(vsid)) {
            if (ACTIVE_YES.equalsIgnoreCase(trimToEmpty(url.getIsActive()))) {
                return LiveUrlFormat.parse(url.getHost());
            }
        }

        return null;
    }

    /** Writes the database values onto the parsed ExecutionMode. */
    private boolean applyToModel(ExecutionMode model, String dbMode, LiveUrlFormat.Parsed activeUrl) {

        boolean changed = false;

        if (!dbMode.equals(trimToEmpty(model.getExeModeValue()))) {
            model.setExeModeValue(dbMode);
            changed = true;
        }

        if (activeUrl == null) {
            return changed;
        }

        ExecutionMode.LiveURL target = activeLiveUrl(model);

        if (target == null) {
            target = new ExecutionMode.LiveURL();
            target.setActive(true);
            model.getLiveURLs().add(target);
            changed = true;
        }

        if (!activeUrl.getTransportType().equals(trimToEmpty(target.getTransportType()))) {
            target.setTransportType(activeUrl.getTransportType());
            changed = true;
        }
        if (!activeUrl.getHost().equals(trimToEmpty(target.getHost()))) {
            target.setHost(activeUrl.getHost());
            changed = true;
        }
        if (!activeUrl.getPort().equals(trimToEmpty(target.getPort()))) {
            target.setPort(activeUrl.getPort());
            changed = true;
        }

        return changed;
    }

    /** The active LiveURL, else the first - the same choice getRouteEndpoint() makes. */
    private ExecutionMode.LiveURL activeLiveUrl(ExecutionMode model) {

        if (model.getLiveURLs() == null || model.getLiveURLs().isEmpty()) {
            return null;
        }

        for (ExecutionMode.LiveURL url : model.getLiveURLs()) {
            if (url.isActive()) {
                return url;
            }
        }

        return model.getLiveURLs().get(0);
    }

    // ---- flow B: no database record, create it from the .vs ----

    private void createRecordsFromVsFile(ServiceConfig config, VsCatalog catalog, String virtServer) {

        String serviceName = config.getServiceName();
        ExecutionMode model = config.getExecutionModeModel();

        if (model == null) {
            Logger.getInstance().info("[ExecutionMode] " + serviceName
                    + ": no ExecutionMode section in the .vs; nothing to record.");
            return;
        }

        Timestamp now = new Timestamp(System.currentTimeMillis());
        String updatedBy = trimToEmpty(config.getUserName());
        Long vsid = vsidRepository.nextVsid();

        VsExecutionMode record = new VsExecutionMode();
        record.setVsid(vsid);
        record.setMasterId(catalog.getMasterId());
        record.setVirtServer(virtServer);
        record.setExecutionMode(trimToEmpty(model.getExeModeValue()));
        record.setUpdateTime(now);
        record.setUpdatedBy(updatedBy);
        executionModeRepository.save(record);

        List<VsLiveUrl> urls = buildLiveUrlRows(model, vsid, now, updatedBy);
        for (VsLiveUrl url : urls) {
            liveUrlRepository.save(url);
        }

        Logger.getInstance().info("[ExecutionMode] " + serviceName
                + ": created database records from the .vs - vsid=" + vsid
                + ", virtServer=" + virtServer
                + ", mode=" + record.getExecutionMode()
                + ", liveUrls=" + urls.size());
    }

    private List<VsLiveUrl> buildLiveUrlRows(ExecutionMode model,
            Long vsid,
            Timestamp now,
            String updatedBy) {

        List<VsLiveUrl> rows = new ArrayList<>();

        if (model.getLiveURLs() == null || model.getLiveURLs().isEmpty()) {
            return rows;
        }

        long nextId = liveUrlRepository.findTopByOrderByVsUrlIdDesc()
                .map(u -> u.getVsUrlId() + 1)
                .orElse(1L);

        boolean activeSeen = false;

        for (ExecutionMode.LiveURL url : model.getLiveURLs()) {

            // BasePath is intentionally left out of the stored host.
            String host = LiveUrlFormat.compose(
                    url.getTransportType(), url.getHost(), url.getPort());

            boolean active = url.isActive() && !activeSeen;
            activeSeen |= active;

            VsLiveUrl row = new VsLiveUrl();
            row.setVsUrlId(nextId++);
            row.setVsid(vsid);
            row.setHost(host);
            row.setIsActive(active ? ACTIVE_YES : ACTIVE_NO);
            row.setUpdateTime(now);
            row.setUpdatedBy(updatedBy);
            rows.add(row);
        }

        // The .vs may mark nothing active; the first URL is what getRouteEndpoint() would have
        // used, so record that as the active one.
        if (!activeSeen) {
            rows.get(0).setIsActive(ACTIVE_YES);
        }

        return rows;
    }

    // ---- helpers ----

    /**
     * The virtual server identity, from stubserver.ip in config.properties.
     *
     * <p>Deliberately has no fallback. Guessing the address is what left the portal and the core
     * disagreeing: the portal derives it from an outbound UDP socket while the core used the
     * hostname lookup, and on a multi-homed or loopback-mapped host those differ, so each side
     * reads a different row.
     */
    private String resolveVirtServer() {

        String configured = trimToEmpty(ConfigLoader.getProperty(STUBSERVER_IP));

        if (configured.isEmpty()) {
            throw new ExecutionModeSyncException(
                    "stubserver.ip is not set in config.properties."
                            + " Set it to this server's IP, then deploy.");
        }

        return configured;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
