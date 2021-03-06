package org.gbif.crawler;

import java.net.URI;
import java.util.UUID;

import com.google.common.base.Objects;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This is the immutable crawl description holding all information needed to run the crawl. This is protocol and
 * dataset specific so we have an implementation for each of our supported protocols. The url is common to them all and
 * needed for the crawler to manage locking.
 */
public class CrawlConfiguration {

  private final UUID datasetKey;

  private final URI url;

  private final int attempt;

  protected CrawlConfiguration(UUID datasetKey, URI url, int attempt) {
    this.datasetKey = checkNotNull(datasetKey, "datasetKey can't be null");
    this.url = checkNotNull(url, "url can't be null");

    checkArgument(attempt > 0, "crawl attempt has to be greater than or equal to 1");
    this.attempt = attempt;
  }

  public int getAttempt() {
    return attempt;
  }

  public UUID getDatasetKey() {
    return datasetKey;
  }

  public URI getUrl() {
    return url;
  }

  @Override
  public boolean equals(Object obj) {
    if (obj == this) {
      return true;
    }
    if (!(obj instanceof CrawlConfiguration)) {
      return false;
    }

    final CrawlConfiguration other = (CrawlConfiguration) obj;
    return Objects.equal(this.datasetKey, other.datasetKey)
           && Objects.equal(this.url, other.url)
           && Objects.equal(this.attempt, other.attempt);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(datasetKey, url, attempt);
  }

  protected Objects.ToStringHelper toStringHelper() {
    return Objects.toStringHelper(getClass()).add("datasetKey", datasetKey).add("url", url).add("attempt", attempt);
  }

}
