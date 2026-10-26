import org.xml.sax.*;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.GZIPInputStream;

/**
 * Generator-style DBLP parser.
 *
 * - nextPublication() blocks until the next publication is available or EOF.
 * - Uses SAX + local DTD resolver (offline, deterministic).
 */
public final class DblpPublicationGenerator implements AutoCloseable {

    public static final class Publication {
        public final String type;              // element name, e.g. "article"
        public final List<String> authors;     // ordered list

        public Publication(String type, List<String> authors) {
            this.type = type;
            this.authors = authors;
        }
    }

    private static final Publication EOF = new Publication("__EOF__", List.of());

    private final BlockingQueue<Publication> queue;
    private final Thread worker;
    private volatile Exception workerError;


    /**
     * Creates a new DBLP publication generator and starts a background parsing thread.
     *
     * The generator parses the DBLP XML file and produces {@code Publication} objects that can be
     * consumed incrementally (publication-by-publication) by the caller
     *
     * @param xmlPath       Path to the DBLP XML input file. This may point to a plain or compressed xml file {@code .xml.gz}.
     * @param dtdPath       Path to the DBLP DTD file ({@code dblp.dtd}). The parser uses it to
     *                      validate/resolve the XML structure locally.
     * @param queueCapacity Maximum number of parsed publications that can be buffered between the
     *                      parsing thread (producer) and the caller (consumer). A larger value may
     *                      improve throughput but uses more memory; a smaller value reduces memory
     *                      usage but may cause the parser thread to block more often.
     */
    public DblpPublicationGenerator(Path xmlPath, Path dtdPath, int queueCapacity) {
        this.queue = new ArrayBlockingQueue<>(Math.max(8, queueCapacity));

        this.worker = new Thread(() -> {
            try {
                parse(xmlPath, dtdPath);
                queue.put(EOF);
            } catch (Exception e) {
                workerError = e;
                try { queue.put(EOF); } catch (InterruptedException ignored) {}
            }
        }, "dblp-parser-worker");

        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * Returns the next publication, or Optional.empty() at EOF.
     * If the parser thread fails, the exception is rethrown here.
     */
    public Optional<Publication> nextPublication() throws Exception {
        Publication p = queue.take();
        if (p == EOF) {
            if (workerError != null) throw workerError;
            return Optional.empty();
        }
        return Optional.of(p);
    }

    private void parse(Path xmlPath, Path dtdPath) throws Exception {
        if (!Files.exists(xmlPath)) throw new FileNotFoundException("XML not found: " + xmlPath);
        if (!Files.exists(dtdPath)) throw new FileNotFoundException("DTD not found: " + dtdPath);

        SAXParserFactory factory = SAXParserFactory.newInstance();
        factory.setNamespaceAware(false);
        factory.setValidating(false);

        trySetFeature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", true);
        trySetFeature(factory, "http://xml.org/sax/features/validation", false);
        trySetFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", false);

        SAXParser parser = factory.newSAXParser();
        XMLReader reader = parser.getXMLReader();
        reader.setEntityResolver(new LocalDtdEntityResolver(dtdPath));
        reader.setContentHandler(new Handler(queue));

        try (InputStream raw = Files.newInputStream(xmlPath);
             InputStream in = wrapMaybeGzip(raw, xmlPath);
             BufferedInputStream bin = new BufferedInputStream(in, 1 << 20)) {

            InputSource src = new InputSource(bin);
            reader.parse(src);
        }
    }

    private static InputStream wrapMaybeGzip(InputStream raw, Path path) throws IOException {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".gz")) return new GZIPInputStream(raw, 1 << 20);
        return raw;
    }

    private static void trySetFeature(SAXParserFactory factory, String feature, boolean value) {
        try { factory.setFeature(feature, value); } catch (Exception ignored) {}
    }

    /**
     * SAX handler that emits one Publication object per record.
     * We deliberately exclude phdthesis/mastersthesis here (as requested).
     */
    private static final class Handler extends DefaultHandler {
        private final BlockingQueue<Publication> out;

        private boolean inRecord = false;
        private boolean inAuthor = false;
        private String recordType = null;

        private final StringBuilder text = new StringBuilder(128);
        private ArrayList<String> authors;

        Handler(BlockingQueue<Publication> out) {
            this.out = out;
        }

        private static boolean isIncludedPublicationType(String qName) {
            return switch (qName) {
                case "article", "inproceedings", "book", "incollection" -> true;
                default -> false;
            };
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            if (!inRecord && isIncludedPublicationType(qName)) {
                inRecord = true;
                recordType = qName;
                authors = new ArrayList<>(8);
                return;
            }

            if (inRecord && qName.equals("author")) {
                inAuthor = true;
                text.setLength(0);
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (inAuthor) text.append(ch, start, length);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (inRecord && qName.equals("author")) {
                inAuthor = false;
                String name = text.toString().trim();
                if (!name.isEmpty()) authors.add(name);
                return;
            }

            if (inRecord && qName.equals(recordType)) {
                inRecord = false;
                try {
                    out.put(new Publication(recordType, authors));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    recordType = null;
                    authors = null;
                }
            }
        }
    }

    /**
     * Local DTD resolver (same idea as before): always load dblp.dtd from disk; block other externals.
     */
    private static final class LocalDtdEntityResolver implements EntityResolver2 {
        private final Path dtdPath;
        private final Path dtdDir;

        LocalDtdEntityResolver(Path dtdPath) {
            this.dtdPath = dtdPath.toAbsolutePath().normalize();
            this.dtdDir = this.dtdPath.getParent();
        }

        @Override
        public InputSource resolveEntity(String name, String publicId, String baseURI, String systemId) throws IOException {
            return resolve(publicId, systemId);
        }

        @Override
        public InputSource resolveEntity(String publicId, String systemId) throws IOException {
            return resolve(publicId, systemId);
        }

        @Override
        public InputSource getExternalSubset(String name, String baseURI) {
            return null;
        }

        private InputSource resolve(String publicId, String systemId) throws IOException {
            if (systemId == null) return emptySource();

            if (systemId.endsWith("dblp.dtd") || systemId.equals("dblp.dtd")) {
                return fileSource(dtdPath, publicId);
            }

            Path candidate = (dtdDir != null) ? dtdDir.resolve(systemId).normalize() : null;
            if (candidate != null && Files.exists(candidate) && Files.isRegularFile(candidate)) {
                return fileSource(candidate, publicId);
            }

            return emptySource();
        }

        private static InputSource fileSource(Path file, String publicId) throws IOException {
            InputSource src = new InputSource(Files.newInputStream(file));
            src.setPublicId(publicId);
            src.setSystemId(file.toUri().toString());
            return src;
        }

        private static InputSource emptySource() {
            return new InputSource(new StringReader(""));
        }
    }

    @Override
    public void close() throws Exception {
        // best-effort shutdown
        worker.interrupt();
        worker.join(10000);
    }
}