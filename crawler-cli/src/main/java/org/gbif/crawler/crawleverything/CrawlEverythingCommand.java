package org.gbif.crawler.crawleverything;

import org.gbif.api.model.common.paging.Pageable;
import org.gbif.api.model.common.paging.PagingRequest;
import org.gbif.api.model.common.paging.PagingResponse;
import org.gbif.api.model.registry.Dataset;
import org.gbif.api.service.registry.DatasetService;
import org.gbif.cli.BaseCommand;
import org.gbif.cli.Command;
import org.gbif.common.messaging.DefaultMessagePublisher;
import org.gbif.common.messaging.api.Message;
import org.gbif.common.messaging.api.MessagePublisher;
import org.gbif.common.messaging.api.messages.StartCrawlMessage;
import org.gbif.registry.ws.client.guice.RegistryWsClientModule;
import org.gbif.ws.client.guice.AnonymousAuthModule;

import java.io.IOException;
import java.util.Properties;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Stopwatch;
import com.google.common.base.Throwables;
import com.google.inject.Guice;
import com.google.inject.Injector;
import org.kohsuke.MetaInfServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This command iterates over all datasets from the registry and sends a StartCrawlMessage for each of them.
 */
@MetaInfServices(Command.class)
public class CrawlEverythingCommand extends BaseCommand {

  private static final Logger LOG = LoggerFactory.getLogger(CrawlEverythingCommand.class);
  private static final int LIMIT = 1000;
  private final EverythingConfiguration config = new EverythingConfiguration();

  public CrawlEverythingCommand() {
    super("crawleverything");
  }

  @Override
  protected Object getConfigurationObject() {
    return config;
  }

  @Override
  protected void doRun() {
    try {
      MessagePublisher publisher = new DefaultMessagePublisher(config.messaging.getConnectionParameters());

      // Create Registry WS Client
      Properties properties = new Properties();
      properties.setProperty("registry.ws.url", config.registryWsUrl);

      Injector injector =
        Guice.createInjector(new RegistryWsClientModule(properties), new AnonymousAuthModule());
      DatasetService datasetService = injector.getInstance(DatasetService.class);

      ExecutorService executor = Executors.newFixedThreadPool(20);

      Random random = new Random();
      int offset = 0;
      AtomicInteger totalCount = new AtomicInteger();
      AtomicInteger scheduledCount = new AtomicInteger();
      boolean endOfRecords = true;
      do {
        Pageable request = new PagingRequest(offset, LIMIT);
        Stopwatch stopwatch = Stopwatch.createStarted();
        LOG.info("Requesting batch of datasets starting at offset [{}]", request.getOffset());
        PagingResponse<Dataset> datasets;
        try {
          datasets = datasetService.list(request);
        } catch (Exception e) {
          offset += LIMIT;
          LOG.error("Got error requesting datasets, skipping to offset [{}]", offset, e);
          continue;
        }
        stopwatch.stop();
        LOG.info("Received [{}] datasets in [{}]s",
                 datasets.getResults().size(),
                 stopwatch.elapsed(TimeUnit.SECONDS));

        endOfRecords = datasets.isEndOfRecords();
        executor.submit(new SchedulingRunnable(datasets, random, publisher, totalCount, scheduledCount, offset));

        offset += datasets.getResults().size();
      } while (!endOfRecords);

      executor.shutdown();
      while (!executor.isTerminated()) {
        try {
          LOG.info("Waiting for completion of scheduling...");
          executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
          LOG.error("Waiting for completion interrupted", e);
        }
      }

      publisher.close();
      LOG.info("Done processing [{}] datasets, [{}] were scheduled", totalCount.get(), scheduledCount.get());
    } catch (IOException e) {
      throw Throwables.propagate(e); // we're hosed
    }
  }

  private static class SchedulingRunnable implements Runnable {

    private final int offset;
    private final PagingResponse<Dataset> datasets;
    private final Random random;
    private final MessagePublisher publisher;
    private final AtomicInteger count;
    private final AtomicInteger scheduledCount;

    private SchedulingRunnable(
      PagingResponse<Dataset> datasets,
      Random random,
      MessagePublisher publisher,
      AtomicInteger count,
      AtomicInteger scheduledCount,
      int offset
    ) {
      this.datasets = datasets;
      this.random = random;
      this.publisher = publisher;
      this.count = count;
      this.offset = offset;
      this.scheduledCount = scheduledCount;
    }

    @Override
    public void run() {
      int registeredCount = 0;
      for (Dataset dataset : datasets.getResults()) {
        count.incrementAndGet();
        if (!dataset.isExternal()) {
          Message message = new StartCrawlMessage(dataset.getKey(), random.nextInt(99) + 1);
          try {
            publisher.send(message);
            registeredCount++;
            scheduledCount.incrementAndGet();
            LOG.debug("Scheduled crawl of [{}]", dataset.getKey());
          } catch (IOException e) {
            LOG.error("Caught exception while sending crawl message", e);
          }
        }
      }
      LOG.debug("[{}] out of [{}] for offset [{}] were registered and scheduled",
                registeredCount,
                datasets.getResults().size(),
                offset);

    }
  }
}