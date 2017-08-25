package org.rutebanken.irkalla.routes.chouette;

import org.apache.activemq.ScheduledMessage;
import org.apache.activemq.command.ActiveMQMessage;
import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.component.http4.HttpMethods;
import org.apache.camel.http.common.HttpOperationFailedException;
import org.rutebanken.irkalla.Constants;
import org.rutebanken.irkalla.IrkallaException;
import org.rutebanken.irkalla.routes.BaseRouteBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static org.rutebanken.irkalla.Constants.HEADER_FULL_SYNC;
import static org.rutebanken.irkalla.util.Http4URL.toHttp4Url;

@Component
public class ChouetteStopPlaceUpdateRouteBuilder extends BaseRouteBuilder {
    @Value("${chouette.url}")
    private String chouetteUrl;


    @Value("${chouette.sync.stop.place.cron:0 0/5 * * * ?}")
    private String deltaSyncCronSchedule;

    @Value("${chouette.sync.stop.place.full.cron:0 0 2 * * ?}")
    private String fullSyncCronSchedule;


    @Value("${chouette.sync.stop.place.retry.delay:15000}")
    private int retryDelay;

    private static final String SYNC_TYPE_PROPERTY ="SyncType";


    @Override
    public void configure() throws Exception {
        super.configure();

        from("quartz2://irkalla/stopPlaceDeltaSync?cron=" + deltaSyncCronSchedule + "&trigger.timeZone=Europe/Oslo")
                .autoStartup("{{chouette.sync.stop.place.autoStartup:true}}")
                .log(LoggingLevel.INFO, "Quartz triggers delta sync of changed stop places.")
                .inOnly("activemq:queue:ChouetteStopPlaceSyncQueue")
                .routeId("chouette-synchronize-stop-places-delta-quartz");

        from("quartz2://irkalla/stopPlaceSync?cron=" + fullSyncCronSchedule + "&trigger.timeZone=Europe/Oslo")
                .autoStartup("{{chouette.sync.stop.place.autoStartup:true}}")
                .log(LoggingLevel.INFO, "Quartz triggers full sync of changed stop places.")
                .setHeader(HEADER_FULL_SYNC, constant(true))
                .inOnly("activemq:queue:ChouetteStopPlaceSyncQueue")
                .routeId("chouette-synchronize-stop-places-full-quartz");

        singletonFrom("activemq:queue:ChouetteStopPlaceSyncQueue?transacted=true&messageListenerContainerFactoryRef=batchListenerContainerFactory")
                .transacted()
                .choice()
                .when(e -> isFullSync(e))
                .setHeader(HEADER_FULL_SYNC, constant(true))
                .setProperty(SYNC_TYPE_PROPERTY,constant("Full"))
                .log(LoggingLevel.INFO, "Full synchronization of stop places in Chouette, deleting unused stops first")
                .to("direct:deleteUnusedStopPlaces")
                .otherwise()
                .setProperty(SYNC_TYPE_PROPERTY,constant("Delta"))
                .setBody(constant(null))
                .to("direct:getSyncStatusUntilTime")
                .setHeader(Constants.HEADER_SYNC_STATUS_FROM, simple("${body}"))
                .log(LoggingLevel.INFO, "Delta synchronization of stop place changes since ${body} in Chouette.")
                .end()
                .setBody(constant(null))
                .setHeader(Constants.HEADER_PROCESS_TARGET, constant("direct:synchronizeStopPlaceBatch"))
                .process(e -> e.getIn().setHeader(Constants.HEADER_SYNC_STATUS_TO, Instant.now()))
                .to("direct:processChangedStopPlacesAsNetex")
                .setBody(simple("${header." + Constants.HEADER_SYNC_STATUS_TO + "}"))
                .to("direct:setSyncStatusUntilTime")
                .log(LoggingLevel.INFO, "${exchangeProperty."+ SYNC_TYPE_PROPERTY +"} synchronization of stop places in Chouette completed.")
                .routeId("chouette-synchronize-stop-places");


        from("direct:deleteUnusedStopPlaces")
                .removeHeaders("CamelHttp*")
                .setBody(constant(null))
                .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.DELETE))
                .doTry()
                .toD(toHttp4Url(chouetteUrl) + "/chouette_iev/stop_place/unused")
                .doCatch(HttpOperationFailedException.class).onWhen(exchange -> {
            HttpOperationFailedException ex = exchange.getException(HttpOperationFailedException.class);
            return (ex.getStatusCode() == 423);
        })
                .log(LoggingLevel.INFO, "Unable to delete unused stop places because Chouette is busy, retry in " + retryDelay + " ms")
                .setHeader(ScheduledMessage.AMQ_SCHEDULED_DELAY, constant(retryDelay))
                .setBody(constant(null))
                .to("activemq:queue:ChouetteStopPlaceSyncQueue")
                .stop()
                .routeId("chouette-synchronize-stop-places-delete-unused");


        from("direct:synchronizeStopPlaceBatch")
                .convertBodyTo(String.class)
                .removeHeaders("CamelHttp*")
                .setHeader(Exchange.HTTP_METHOD, constant(HttpMethods.POST))
                .doTry()
                .toD(toHttp4Url(chouetteUrl) + "/chouette_iev/stop_place")
                .doCatch(HttpOperationFailedException.class).onWhen(exchange -> {
            HttpOperationFailedException ex = exchange.getException(HttpOperationFailedException.class);
            return (ex.getStatusCode() == 423);
        })
                .log(LoggingLevel.INFO, "Unable to sync stop places because Chouette is busy, retry in " + retryDelay + " ms")
                .setHeader(ScheduledMessage.AMQ_SCHEDULED_DELAY, constant(retryDelay))
                .setBody(constant(null))
                .to("activemq:queue:ChouetteStopPlaceSyncQueue")
                .stop()
                .routeId("chouette-synchronize-stop-place-batch");

    }

    private boolean isFullSync(Exchange e) {
        for (Object o : e.getIn().getBody(List.class)) {
            if (o instanceof ActiveMQMessage) {
                ActiveMQMessage activeMQMessage = (ActiveMQMessage) o;
                try {
                    Object prop = activeMQMessage.getProperty(HEADER_FULL_SYNC);

                    if (prop != null && Boolean.TRUE.equals(prop)) {;
                        return true;
                    }
                } catch (IOException ioE) {
                    throw new IrkallaException("Unable to get fullSync header as property from ActiveMQMessage: " + ioE.getMessage(), ioE);
                }
            }
        }
        return false;
    }
}
