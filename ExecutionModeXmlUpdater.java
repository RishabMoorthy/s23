package org.framework.utils;

import org.framework.config.ExecutionModeType;
import org.framework.constants.PathConstants;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.File;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

/**
 * Writes execution-mode changes back into a service's .vs file.
 *
 * <p>Only the root ExecutionMode subtree is touched - the ExecutionModeValue and
 * the active LiveURL's TransType / Host / Port. Every other byte of the document
 * is left exactly as it was found.
 *
 * <p>VirtualServiceXmlWriter is deliberately not used for this. Rebuilding the
 * whole document from the parsed model drops content the parser never captured
 * (DataSourceSelect ResultProperties, DataFile RequestName) and injects default
 * nodes that were not in the original, so a mode change would quietly corrupt
 * unrelated sections of the file.
 *
 * <p>BasePath is never read or written here: it is not part of the live endpoint.
 */
public final class ExecutionModeXmlUpdater {

    private static final String EXECUTION_MODE = "ExecutionMode";
    private static final String EXECUTION_MODE_VALUE = "ExecutionModeValue";
    private static final String LIVE_URLS = "LiveURLs";
    private static final String LIVE_URL = "LiveURL";
    private static final String ENV_TYPE = "EnvType";
    private static final String BASE_PATH = "BasePath";
    private static final String TRANS_TYPE = "TransType";
    private static final String HOST = "Host";
    private static final String PORT = "Port";
    private static final String ACTIVE = "active";

    private ExecutionModeXmlUpdater() {
    }

    /** Outcome of an update attempt. */
    public static final class Result {

        private final boolean changed;
        private final String xmlContent;

        private Result(boolean changed, String xmlContent) {
            this.changed = changed;
            this.xmlContent = xmlContent;
        }

        /** True when the file on disk was rewritten. */
        public boolean isChanged() {
            return changed;
        }

        /** The document as it now stands, for ServiceConfig.setXmlFileContent. */
        public String getXmlContent() {
            return xmlContent;
        }
    }

    /**
     * Applies the mode and the active live URL to the service's .vs file.
     *
     * @param serviceName   service name - the .vs is vsfiles/&lt;serviceName&gt;.xml
     * @param xmlPathHint   VirtualServiceObject.getXmlPath(), used only if the
     *                      conventional location does not exist. May be a file: URI.
     * @param executionMode mode to write; skipped when null or blank
     * @param liveUrl       parsed active live URL; skipped when null
     * @return what happened, never null
     */
    public static Result apply(String serviceName,
            String xmlPathHint,
            String executionMode,
            LiveUrlFormat.Parsed liveUrl) throws Exception {

        Path file = resolveXmlFile(serviceName, xmlPathHint);
        if (file == null) {
            Logger.getInstance().info("[ExecutionMode] No .vs file found for " + serviceName
                    + "; in-memory execution mode applied, file left untouched.");
            return new Result(false, null);
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        DocumentBuilder builder = factory.newDocumentBuilder();
        Document doc = builder.parse(file.toFile());

        Element root = doc.getDocumentElement();
        Element executionModeEl = firstChild(root, EXECUTION_MODE);
        if (executionModeEl == null) {
            Logger.getInstance().info("[ExecutionMode] " + serviceName
                    + " has no root ExecutionMode element; file left untouched.");
            return new Result(false, null);
        }

        boolean changed = false;
        boolean modeKnown = executionMode != null && !executionMode.isBlank();

        if (modeKnown) {
            changed |= setModeValue(doc, executionModeEl, executionMode.trim());
        }

        // The shape of the section follows the mode. Stand-In and an unset mode carry an
        // empty <LiveURLs/>; only the two live modes carry a LiveURL. Without this, switching
        // Live Invocation -> Stand-In would leave a stale host and port behind in the file.
        if (modeKnown && !ExecutionModeType.from(executionMode).usesLiveUrls()) {
            changed |= clearLiveUrls(executionModeEl);
        } else if (liveUrl != null) {
            changed |= setActiveLiveUrl(doc, executionModeEl, liveUrl);
        }

        String xml = serialize(doc);

        if (!changed) {
            return new Result(false, xml);
        }

        writeAtomically(file, xml);
        Logger.getInstance().info("[ExecutionMode] " + serviceName + ": updated " + file);

        return new Result(true, xml);
    }

    // ---- ExecutionModeValue ----

    private static boolean setModeValue(Document doc, Element executionModeEl, String mode) {

        Element valueEl = firstChild(executionModeEl, EXECUTION_MODE_VALUE);

        if (valueEl == null) {
            valueEl = createElement(doc, executionModeEl, EXECUTION_MODE_VALUE);
            executionModeEl.insertBefore(valueEl, executionModeEl.getFirstChild());
            valueEl.setTextContent(mode);
            return true;
        }

        if (mode.equals(text(valueEl))) {
            return false;
        }

        valueEl.setTextContent(mode);
        return true;
    }

    // ---- active LiveURL ----

    private static boolean setActiveLiveUrl(Document doc,
            Element executionModeEl,
            LiveUrlFormat.Parsed liveUrl) {

        Element liveUrlsEl = firstChild(executionModeEl, LIVE_URLS);
        if (liveUrlsEl == null) {
            liveUrlsEl = createElement(doc, executionModeEl, LIVE_URLS);
            executionModeEl.appendChild(liveUrlsEl);
        }

        Element target = activeLiveUrl(liveUrlsEl);

        if (target == null) {
            // Nothing in the file to update - mirror the DB's active URL so the document and
            // the database agree, in the same element order the portal writes.
            createLiveUrl(doc, liveUrlsEl, liveUrl);
            return true;
        }

        boolean changed = false;
        changed |= setChildText(doc, target, TRANS_TYPE, liveUrl.getTransportType());
        changed |= setChildText(doc, target, HOST, liveUrl.getHost());
        changed |= setChildText(doc, target, PORT, liveUrl.getPort());

        return changed;
    }

    /**
     * Empties the LiveURLs element, leaving {@code <LiveURLs/>} in place.
     *
     * <p>The element itself is kept rather than removed, because that is the shape the file
     * carries for Stand-In and for an unset mode. Nothing is lost: the URLs live in
     * VS_LIVEURLS, and switching back to a live mode restores them from there.
     */
    private static boolean clearLiveUrls(Element executionModeEl) {

        Element liveUrlsEl = firstChild(executionModeEl, LIVE_URLS);
        if (liveUrlsEl == null) {
            return false;
        }

        boolean removed = false;
        Node child = liveUrlsEl.getFirstChild();

        while (child != null) {
            Node next = child.getNextSibling();
            boolean isLiveUrl = child instanceof Element el && LIVE_URL.equals(el.getLocalName());
            boolean isLayoutText = child.getNodeType() == Node.TEXT_NODE
                    && (child.getNodeValue() == null || child.getNodeValue().isBlank());

            if (isLiveUrl) {
                liveUrlsEl.removeChild(child);
                removed = true;
            } else if (isLayoutText) {
                // Drop the indentation left behind, so the element collapses to <LiveURLs/>
                // rather than an empty pair wrapped around stray whitespace.
                liveUrlsEl.removeChild(child);
            }
            child = next;
        }

        return removed;
    }

    /** The LiveURL marked active, else the first one - matching ServiceConfig.getRouteEndpoint(). */
    private static Element activeLiveUrl(Element liveUrlsEl) {

        Element first = null;

        for (Node n = liveUrlsEl.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element el) || !LIVE_URL.equals(el.getLocalName())) {
                continue;
            }
            if (first == null) {
                first = el;
            }
            if (Boolean.parseBoolean(el.getAttribute(ACTIVE))) {
                return el;
            }
        }

        return first;
    }

    private static boolean setChildText(Document doc, Element parent, String name, String value) {

        String newValue = value == null ? "" : value;
        Element child = firstChild(parent, name);

        if (child == null) {
            child = createElement(doc, parent, name);
            parent.appendChild(child);
            child.setTextContent(newValue);
            return true;
        }

        if (newValue.equals(text(child))) {
            return false;
        }

        child.setTextContent(newValue);
        return true;
    }

    // ---- file handling ----

    /** vsfiles/&lt;name&gt;.xml, falling back to the parser's own path. */
    private static Path resolveXmlFile(String serviceName, String xmlPathHint) {

        Path conventional = Paths.get(PathConstants.VS_XML_DIRECTORY, serviceName + ".xml");
        if (Files.exists(conventional)) {
            return conventional;
        }

        if (xmlPathHint != null && !xmlPathHint.isBlank()) {
            try {
                Path hinted = xmlPathHint.startsWith("file:")
                        ? Paths.get(URI.create(xmlPathHint))
                        : Paths.get(xmlPathHint);
                if (Files.exists(hinted)) {
                    return hinted;
                }
            } catch (Exception e) {
                Logger.getInstance().info("[ExecutionMode] Unusable xmlPath for " + serviceName
                        + ": " + xmlPathHint);
            }
        }

        return null;
    }

    /**
     * Writes through a temp file in the same directory, then swaps it in.
     *
     * <p>CustomMethods.backupXmlFile is not used: it moves rather than copies, so
     * it would remove the file being edited, and it overwrites vsfiles/backup,
     * which is the revert point for the last deploy.
     */
    private static void writeAtomically(Path file, String xml) throws Exception {

        Path directory = file.toAbsolutePath().getParent();
        Path temp = Files.createTempFile(directory, "execmode-", ".xml");

        try {
            Files.writeString(temp, xml);
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception atomicNotSupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static String serialize(Document doc) throws Exception {
        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter out = new StringWriter();
        transformer.transform(new DOMSource(doc), new StreamResult(out));
        return out.toString();
    }

    /**
     * Writes a complete LiveURL in the order the portal writes it: EnvType, TransType, Host,
     * Port, BasePath.
     *
     * <p>EnvType and BasePath are emitted empty. Neither is stored in the database - BasePath
     * plays no part in the live endpoint, and EnvType is not synced - so a value cannot be
     * recovered here once the element has been cleared by a Stand-In switch.
     *
     * <p>Indentation is copied from the surrounding document so the rewritten block matches
     * the rest of the file.
     */
    private static void createLiveUrl(Document doc, Element liveUrlsEl, LiveUrlFormat.Parsed liveUrl) {

        String outer = indentBefore(liveUrlsEl, "\n    ");
        String inner = outer + "    ";
        String innermost = inner + "    ";

        Element urlEl = createElement(doc, liveUrlsEl, LIVE_URL);
        urlEl.setAttribute(ACTIVE, "true");

        appendChildText(doc, urlEl, ENV_TYPE, "", innermost);
        appendChildText(doc, urlEl, TRANS_TYPE, liveUrl.getTransportType(), innermost);
        appendChildText(doc, urlEl, HOST, liveUrl.getHost(), innermost);
        appendChildText(doc, urlEl, PORT, liveUrl.getPort(), innermost);
        appendChildText(doc, urlEl, BASE_PATH, "", innermost);
        urlEl.appendChild(doc.createTextNode(inner));

        liveUrlsEl.appendChild(doc.createTextNode(inner));
        liveUrlsEl.appendChild(urlEl);
        liveUrlsEl.appendChild(doc.createTextNode(outer));
    }

    private static void appendChildText(Document doc,
            Element parent,
            String name,
            String value,
            String indent) {

        parent.appendChild(doc.createTextNode(indent));
        Element el = createElement(doc, parent, name);
        if (value != null && !value.isEmpty()) {
            el.setTextContent(value);
        }
        parent.appendChild(el);
    }

    /** The whitespace run immediately before an element, used to match existing indentation. */
    private static String indentBefore(Element el, String fallback) {

        Node previous = el.getPreviousSibling();

        if (previous != null
                && previous.getNodeType() == Node.TEXT_NODE
                && previous.getNodeValue() != null
                && previous.getNodeValue().isBlank()
                && previous.getNodeValue().contains("\n")) {

            String text = previous.getNodeValue();
            return text.substring(text.lastIndexOf('\n'));
        }

        return fallback;
    }

    // ---- DOM helpers ----

    private static Element firstChild(Element parent, String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && localName.equals(el.getLocalName())) {
                return el;
            }
        }
        return null;
    }

    /** Creates an element carrying the same namespace and prefix as its siblings. */
    private static Element createElement(Document doc, Element sibling, String localName) {
        String namespace = sibling.getNamespaceURI();
        String prefix = sibling.getPrefix();
        if (namespace == null) {
            return doc.createElement(localName);
        }
        return doc.createElementNS(namespace,
                prefix == null || prefix.isBlank() ? localName : prefix + ":" + localName);
    }

    private static String text(Element el) {
        String value = el.getTextContent();
        return value == null ? "" : value.trim();
    }
}
