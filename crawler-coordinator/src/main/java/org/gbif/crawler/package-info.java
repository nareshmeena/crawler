/**
 * This is a library providing services to coordinate crawls.
 *
 * The two things implemented at the moment are:
 * <ul>
 *   <li>Coordination</li>
 *   <li>Metrics</li>
 * </ul>
 *
 * <h1>Components</h1>
 *
 * <h2>Coordination</h2>
 *
 * Defined in the {@link CrawlerCoordinatorService} interface and implemented in the
 * {@link CrawlerCoordinatorServiceImpl} class. Allows to initiate crawls.
 *
 * <h2>Metrics</h2>
 *
 * Defined in the {@link org.gbif.api.service.crawler.CrawlerDetailService} interface and implemented in the {@link DatasetProcessServiceImpl}
 * class. Can be used to return metrics about running crawls.
 *
 * <h1>Usage</h1>
 *
 * As this is only a library you need to write something using these services. That could be a Web service, a CLI or
 * something else entirely.
 */
package org.gbif.crawler;
