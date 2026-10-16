package com.noteweave.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchSourceIndependenceTest {
    @Test
    void shouldUsePublicAndPrivateSuffixes() {
        assertThat(ResearchSourceIndependence.registeredDomain("docs.example.co.nz")).isEqualTo("example.co.nz");
        assertThat(ResearchSourceIndependence.registeredDomain("a.github.io")).isNotEqualTo(ResearchSourceIndependence.registeredDomain("b.github.io"));
    }

    @Test
    void shouldComputeNoisyOrAndCapIndependentSourceConfidence() {
        var first = source("alpha.org", "", "Export data through CSV format", 800000);
        var second = source("beta.net", "", "Download complete records in spreadsheet files", 700000);
        assertThat(ResearchSourceIndependence.score(List.of(first, second))).isEqualTo(new ResearchSourceIndependence.Score(2, 940000));
        assertThat(ResearchSourceIndependence.score(List.of(first, source("beta.net", "", second.quote(), 900000))).confidencePpm()).isEqualTo(950000);
    }

    @Test
    void shouldMergeDomainAndQuoteCopiesTransitively() {
        var sources = List.of(source("docs.example.co.nz", "", "first original passage", 800000),
                source("www.example.co.nz", "", "copied exact passage", 700000),
                source("independent.org", "", "copied exact passage", 600000));
        assertThat(ResearchSourceIndependence.score(sources)).isEqualTo(new ResearchSourceIndependence.Score(1, 800000));
    }

    @Test
    void shouldDetectReorderedRepublicationWithSimhash() {
        String first = "alpha beta gamma delta epsilon", second = "epsilon delta gamma beta alpha";
        assertThat(ResearchSourceIndependence.simhash(first)).isEqualTo(ResearchSourceIndependence.simhash(second));
        assertThat(String.format("%016x", ResearchSourceIndependence.simhash(first))).isEqualTo("eedf74ad6fe9f881");
        assertThat(String.format("%016x", ResearchSourceIndependence.simhash("Straße 中文 ＡＢＣ"))).isEqualTo("367bbfbf81574d22");
        assertThat(ResearchSourceIndependence.score(List.of(source("alpha.org", null, first, 800000), source("beta.net", null, second, 700000))).independentSources()).isEqualTo(1);
        assertThat(ResearchSourceIndependence.overlappingFingerprints(List.of(ResearchSourceIndependence.fingerprint(first)), List.of(ResearchSourceIndependence.fingerprint(second)))).isTrue();
    }

    private ResearchSourceIndependence.Source source(String domain, String lineage, String quote, int confidence) {
        return new ResearchSourceIndependence.Source(domain, lineage, quote, confidence);
    }
}
