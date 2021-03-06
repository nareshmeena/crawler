package org.gbif.crawler.crawleverything;

import org.gbif.api.model.common.paging.Pageable;
import org.gbif.api.model.common.paging.PagingRequest;
import org.gbif.api.model.common.paging.PagingResponse;
import org.gbif.api.model.registry.Installation;
import org.gbif.api.service.registry.InstallationService;
import org.gbif.cli.BaseCommand;
import org.gbif.cli.Command;
import org.gbif.common.messaging.DefaultMessagePublisher;
import org.gbif.common.messaging.api.Message;
import org.gbif.common.messaging.api.MessagePublisher;
import org.gbif.common.messaging.api.messages.StartMetasyncMessage;
import org.gbif.registry.ws.client.guice.RegistryWsClientModule;
import org.gbif.ws.client.guice.AnonymousAuthModule;

import java.io.IOException;
import java.util.Properties;
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
 * This command iterates over all installations from the registry and sends a StartMetasyncMessage for each of them.
 */
@MetaInfServices(Command.class)
public class MetasyncEverythingCommand extends BaseCommand {

  private static final Logger LOG = LoggerFactory.getLogger(CrawlEverythingCommand.class);
  private static final int LIMIT = 1000;
  private final EverythingConfiguration config = new EverythingConfiguration();

  public MetasyncEverythingCommand() {
    super("metasynceverything");
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

      Injector injector = Guice.createInjector(new RegistryWsClientModule(properties), new AnonymousAuthModule());

      int offset = 0;
      boolean endOfRecords = true;
      ExecutorService executor = Executors.newFixedThreadPool(20);
      AtomicInteger totalCount = new AtomicInteger();
      AtomicInteger scheduledCount = new AtomicInteger();
      InstallationService installationService = injector.getInstance(InstallationService.class);
      do {
        Pageable request = new PagingRequest(offset, LIMIT);
        Stopwatch stopwatch = new Stopwatch().start();
        LOG.info("Requesting batch of installations starting at offset [{}]", request.getOffset());
        PagingResponse<Installation> installations;
        try {
          installations = installationService.list(request);
        } catch (Exception e) {
          offset += LIMIT;
          LOG.error("Got error requesting installations, skipping to offset [{}]", offset, e);
          continue;
        }
        stopwatch.stop();
        LOG.info("Received [{}] installations in [{}]s", installations.getResults().size(),
          stopwatch.elapsed(TimeUnit.SECONDS));
        executor
          .submit(new InstallationSchedulingRunnable(installations, publisher, totalCount, scheduledCount, offset));

        endOfRecords = installations.isEndOfRecords();
        offset += installations.getResults().size();
      } while (!endOfRecords);

      executor.shutdown();
      while (!executor.isTerminated()) {
        try {
          LOG.info("Waiting for completion of metasync...");
          executor.awaitTermination(1, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
          LOG.error("Waiting for completion interrupted", e);
        }
      }
      publisher.close();
      LOG.info("Done processing [{}] installations, [{}] were scheduled", totalCount.get(), scheduledCount.get());
    } catch (IOException e) {
      throw Throwables.propagate(e); // we're hosed
    }
  }

  private static class InstallationSchedulingRunnable implements Runnable {

    private final int offset;
    private final PagingResponse<Installation> installations;
    private final MessagePublisher publisher;
    private final AtomicInteger count;
    private final AtomicInteger scheduledCount;

    private InstallationSchedulingRunnable(PagingResponse<Installation> installations, MessagePublisher publisher,
      AtomicInteger count, AtomicInteger scheduledCount, int offset) {
      this.installations = installations;
      this.publisher = publisher;
      this.count = count;
      this.offset = offset;
      this.scheduledCount = scheduledCount;
    }

    @Override
    public void run() {
      int registeredCount = 0;
      for (Installation installation : installations.getResults()) {
        count.incrementAndGet();
        Message message = new StartMetasyncMessage(installation.getKey());
        try {
          publisher.send(message);
          registeredCount++;
          scheduledCount.incrementAndGet();
          LOG.debug("Scheduled metasync of installation [{}]", installation.getKey());
        } catch (IOException e) {
          LOG.error("Caught exception while sending metasync message", e);
        }
      }
      LOG.debug("[{}] installations out of [{}] for offset [{}] were registered and scheduled", registeredCount,
        installations.getResults().size(), offset);
    }
  }
}
