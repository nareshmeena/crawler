package org.gbif.crawler.xml.crawlserver;

import org.gbif.api.model.crawler.CrawlJob;
import org.gbif.common.messaging.api.MessagePublisher;
import org.gbif.crawler.Crawler;
import org.gbif.crawler.common.CrawlConsumer;
import org.gbif.crawler.strategy.ScientificNameRangeCrawlContext;
import org.gbif.crawler.strategy.ScientificNameRangeStrategy;
import org.gbif.crawler.xml.crawlserver.builder.CrawlerBuilder;
import org.gbif.crawler.xml.crawlserver.listener.CrawlerZooKeeperUpdatingListener;
import org.gbif.crawler.xml.crawlserver.listener.LoggingCrawlListener;
import org.gbif.crawler.xml.crawlserver.listener.MessagingCrawlListener;
import org.gbif.crawler.xml.crawlserver.listener.ResultPersistingListener;

import java.io.File;
import java.util.List;
import java.util.UUID;

import com.google.common.base.Optional;
import org.apache.curator.framework.CuratorFramework;
import org.apache.http.HttpResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class XmlCrawlConsumer extends CrawlConsumer {
  private static final Logger LOG = LoggerFactory.getLogger(XmlCrawlConsumer.class);
  private static final int DEFAULT_CONCURRENT_CONNECTIONS = 2;
  private static final String CONCURRENT_CONNECTIONS_PROPERTY = "concurrentConnections";

  private final Optional<Long> minDelay;
  private final Optional<Long> maxDelay;
  private final File responseArchive;

  // The key used to store the count of records declared by the publisher in the Registry as a machine tag
  private static final String KEY_DECLARED_COUNT = "declaredCount";

  // Less than 1000 records will be requested in a single range
  private static final int SINGLE_PAGE_THRESHOLD = 1000;

  // Less 1000->10000 records will be requested in pages by Letter (A,B,C etc)
  private static final int ABC_THRESHOLD = 10000;

  /**
   * @param minDelay is the "grace" period in milliseconds we allow between calls to a URL implemented by waiting to
   *                 release the lock on the URL for this amount of time
   */
  XmlCrawlConsumer(CuratorFramework curator, MessagePublisher publisher, Optional<Long> minDelay,
    Optional<Long> maxDelay, File responseArchive) {
    super(curator, publisher);
    this.minDelay = minDelay;
    this.maxDelay = maxDelay;
    this.responseArchive = responseArchive;
  }

  @Override
  protected void crawl(UUID datasetKey, CrawlJob crawlJob) {
    ScientificNameRangeStrategy.Mode mode = determineMode(crawlJob);
    CrawlerBuilder builder =
      CrawlerBuilder.buildFor(crawlJob).withDefaultRetryStrategy().withScientificNameRangeCrawlContext(mode);

    int concurrentConnections = DEFAULT_CONCURRENT_CONNECTIONS;
    if (crawlJob.getProperties().containsKey(CONCURRENT_CONNECTIONS_PROPERTY)) {
      concurrentConnections = Integer.parseInt(crawlJob.getProperty(CONCURRENT_CONNECTIONS_PROPERTY));
    }

    builder.withZooKeeperLock(curator, concurrentConnections);

    if (minDelay.isPresent()) {
      builder.withDelayedLock(minDelay.get(), maxDelay.get());
    }

    Crawler<ScientificNameRangeCrawlContext, String, HttpResponse, List<Byte>> crawler = builder.build();

    crawler.addListener(
      new CrawlerZooKeeperUpdatingListener<ScientificNameRangeCrawlContext>(builder.getCrawlConfiguration(), curator));
    crawler.addListener(new LoggingCrawlListener<ScientificNameRangeCrawlContext>(builder.getCrawlConfiguration()));
    crawler.addListener(
      new MessagingCrawlListener<ScientificNameRangeCrawlContext>(publisher, builder.getCrawlConfiguration()));
    if (responseArchive != null) {
      crawler.addListener(new ResultPersistingListener(responseArchive, builder.getCrawlConfiguration()));
    }

    // This blocks until the crawl is finished.
    crawler.crawl();

    // Note that zookeeper finish updates are handled by the CrawlerZooKeeperUpdatingListener!
  }

  /**
   * Determines the mode of name range paging to use, based on any declared record count in the crawlJob.
   * Thresholds are used
   */
  private static ScientificNameRangeStrategy.Mode determineMode(CrawlJob crawlJob) {
    String declaredCount = crawlJob.getProperty(KEY_DECLARED_COUNT);
    if (declaredCount != null) {
      try {
        int count = Integer.parseInt(declaredCount);
        if (count < SINGLE_PAGE_THRESHOLD) {
          LOG.info("Using SINGLE PAGE crawl mode as declared count of {} is below threshold of {}",
                   count, SINGLE_PAGE_THRESHOLD);
          return ScientificNameRangeStrategy.Mode.AZ;
        } else if (count < ABC_THRESHOLD) {
          LOG.info("Using A,B,C crawl mode as declared count of {} is below threshold of {}",
                   count, ABC_THRESHOLD);
          return ScientificNameRangeStrategy.Mode.ABC;
        } else {
          LOG.info("Using DEFAULT crawl mode as declared count of {} is above threshold of {}",
                   count, ABC_THRESHOLD);
          return ScientificNameRangeStrategy.Mode.DEFAULT;
        }
      } catch (NumberFormatException e) {
        LOG.info("Using DEFAULT crawl mode due to unreadable count, which is not an integer: {}", declaredCount);
        return ScientificNameRangeStrategy.Mode.DEFAULT;

      }
    }
    LOG.info("Using DEFAULT crawl mode as no declared count found");
    return ScientificNameRangeStrategy.Mode.DEFAULT;
  }
}
