package io.github.definitelystable.spongetube.medialab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

class DashFixtureContractTest {

    private static final String DASH_NS = "urn:mpeg:dash:schema:mpd:2011";

    @Test
    void f1IsStaticSingleVideoSingleAudioDash() throws Exception {
        Document document = parse(fixtureRoot().resolve("F1/manifest.mpd"));
        Element mpd = document.getDocumentElement();

        assertEquals("MPD", mpd.getLocalName());
        assertEquals("static", mpd.getAttribute("type"));
        assertEquals("PT3M0.0S", mpd.getAttribute("mediaPresentationDuration"));

        NodeList adaptationSets = document.getElementsByTagNameNS(DASH_NS, "AdaptationSet");
        assertEquals(2, adaptationSets.getLength());

        Map<String, Element> representationsByType = new HashMap<>();
        for (int index = 0; index < adaptationSets.getLength(); index++) {
            Element adaptation = (Element) adaptationSets.item(index);
            String contentType = adaptation.getAttribute("contentType");
            NodeList representations = adaptation.getElementsByTagNameNS(DASH_NS, "Representation");
            assertEquals(1, representations.getLength(), contentType + " must have one representation");
            representationsByType.put(contentType, (Element) representations.item(0));
        }

        Element video = representationsByType.get("video");
        Element audio = representationsByType.get("audio");
        assertNotNull(video);
        assertNotNull(audio);

        assertEquals("0", video.getAttribute("id"));
        assertEquals("video/mp4", video.getAttribute("mimeType"));
        assertEquals("avc1.4d401e", video.getAttribute("codecs"));

        assertEquals("1", audio.getAttribute("id"));
        assertEquals("audio/mp4", audio.getAttribute("mimeType"));
        assertEquals("mp4a.40.2", audio.getAttribute("codecs"));

        assertSegmentTemplate(video, "init-$RepresentationID$.m4s",
                "segment-$RepresentationID$-$Number%05d$.m4s");
        assertSegmentTemplate(audio, "init-$RepresentationID$.m4s",
                "segment-$RepresentationID$-$Number%05d$.m4s");

        assertTimelineCoverage(video, 180L);
        assertTimelineCoverage(audio, 180L);
    }

    private static void assertSegmentTemplate(
            Element representation,
            String expectedInitialization,
            String expectedMedia) {
        NodeList templates = representation.getElementsByTagNameNS(DASH_NS, "SegmentTemplate");
        assertEquals(1, templates.getLength());

        Element template = (Element) templates.item(0);
        assertEquals(expectedInitialization, template.getAttribute("initialization"));
        assertEquals(expectedMedia, template.getAttribute("media"));
        assertEquals("1", template.getAttribute("startNumber"));
    }

    private static void assertTimelineCoverage(Element representation, long expectedSeconds) {
        Element template = (Element) representation
                .getElementsByTagNameNS(DASH_NS, "SegmentTemplate")
                .item(0);
        long timescale = Long.parseLong(template.getAttribute("timescale"));

        Element timeline = (Element) template
                .getElementsByTagNameNS(DASH_NS, "SegmentTimeline")
                .item(0);
        NodeList segments = timeline.getElementsByTagNameNS(DASH_NS, "S");

        long totalUnits = 0;
        for (int index = 0; index < segments.getLength(); index++) {
            Element segment = (Element) segments.item(index);
            long duration = Long.parseLong(segment.getAttribute("d"));
            long repeat = segment.hasAttribute("r")
                    ? Long.parseLong(segment.getAttribute("r"))
                    : 0L;
            if (repeat < 0) {
                throw new AssertionError("Canonical F1 must not use open-ended SegmentTimeline repeats");
            }
            totalUnits = Math.addExact(
                    totalUnits,
                    Math.multiplyExact(duration, repeat + 1L));
        }

        assertEquals(
                Math.multiplyExact(timescale, expectedSeconds),
                totalUnits,
                representation.getAttribute("id") + " timeline coverage");
    }

    private static Document parse(Path path) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(path.toFile());
    }

    private static Path fixtureRoot() {
        String configured = System.getProperty("spongetube.fixtureRoot");
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("Missing spongetube.fixtureRoot test property");
        }
        return Path.of(configured);
    }
}
