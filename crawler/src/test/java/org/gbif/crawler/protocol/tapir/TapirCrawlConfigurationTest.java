package org.gbif.crawler.protocol.tapir;

import java.net.URI;
import java.util.UUID;

import org.junit.Test;

import static org.fest.assertions.api.Assertions.assertThat;
import static org.junit.Assert.fail;

public class TapirCrawlConfigurationTest {

  @Test
  public void testJob() {
    URI targetUrl = URI.create("http://mockhost1.gbif.org/tapirlink/tapir.php/pontaurus");
    UUID uuid = UUID.randomUUID();
    String contentNamespace = "http://rs.tdwg.org/dwc/dwcore/";

    testFailure(null, 1, targetUrl, contentNamespace, "datasetKey");
    testFailure(uuid, 0, targetUrl, contentNamespace, "attempt");
    testFailure(uuid, -10, targetUrl, contentNamespace, "attempt");
    testFailure(uuid, 1, null, contentNamespace, "url");
    testFailure(uuid, 1, targetUrl, null, "Namespace");
    testFailure(uuid, 1, targetUrl, "foobar", "support");

    TapirCrawlConfiguration job = new TapirCrawlConfiguration(uuid, 1, targetUrl, contentNamespace);

    assertThat(job.getContentNamespace()).isEqualTo(contentNamespace);
    assertThat(job.getUrl()).isEqualTo(targetUrl);
    assertThat(job.getAttempt()).isEqualTo(1);
    assertThat(job.getDatasetKey()).isEqualTo(uuid);
  }

  private void testFailure(UUID uuid, int attempt, URI url, String contentNamespace, String expectedString) {
    try {
      new TapirCrawlConfiguration(uuid, attempt, url, contentNamespace);
      fail();
    } catch (Exception ex) {
      assertThat(ex).hasMessageContaining(expectedString);
    }
  }

}
