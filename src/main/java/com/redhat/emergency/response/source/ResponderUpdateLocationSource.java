package com.redhat.emergency.response.source;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;

import com.redhat.emergency.response.model.Mission;
import com.redhat.emergency.response.model.MissionStatus;
import com.redhat.emergency.response.model.ResponderLocationHistory;
import com.redhat.emergency.response.model.ResponderLocationStatus;
import com.redhat.emergency.response.repository.MissionRepository;
import com.redhat.emergency.response.sink.EventSink;
import io.smallrye.mutiny.Uni;
import io.smallrye.reactive.messaging.ce.IncomingCloudEventMetadata;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ApplicationScoped
public class ResponderUpdateLocationSource {

    static final String RESPONDER_LOCATION_UPDATED_EVENT = "ResponderLocationUpdatedEvent";
    static final String[] ACCEPTED_MESSAGE_TYPES = {RESPONDER_LOCATION_UPDATED_EVENT};

    @Inject
    MissionRepository repository;

    @Inject
    EventSink eventSink;

    private static final Logger log = LoggerFactory.getLogger(ResponderUpdateLocationSource.class);

    @Incoming("responder-location-update")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public Uni<CompletionStage<Void>> process(Message<String> responderLocationUpdate) {

        return Uni.createFrom().item(responderLocationUpdate).onItem()
                .transform(m -> getLocationUpdate(responderLocationUpdate))
                .onItem().ifNotNull().transformToUni(this::processLocationUpdate)
                .onItem().transform(v -> responderLocationUpdate.ack())
                .onFailure().recoverWithItem(t -> {
                    log.error(t.getMessage(), t);
                    return responderLocationUpdate.ack();
                });
    }

    private Uni<Void> processLocationUpdate(JsonObject locationUpdate) {
        Uni<Optional<Mission>> mission = repository.get(getKey(locationUpdate));
        return mission.map(m -> {
            if (m.isPresent()) {
                ResponderLocationHistory rlh = new ResponderLocationHistory(BigDecimal.valueOf(locationUpdate.getDouble("lat")),
                        BigDecimal.valueOf(locationUpdate.getDouble("lon")), Instant.now().toEpochMilli());
                m.get().getResponderLocationHistory().add(rlh);
                return m.get();
            } else {
                log.warn("Mission with key = " + getKey(locationUpdate) + " could not be retrieved of could not be not found in the repository.");
                return null;
            }
        })
                .onItem().ifNotNull().transformToUni(m -> emitMissionEvent(locationUpdate.getString("status"), m))
                .onItem().ifNotNull().transformToUni(m -> repository.add(m))
                .onItem().transformToUni(m -> Uni.createFrom().item(() -> null));
    }

    private Uni<Mission> emitMissionEvent(String status, Mission mission) {
        if (ResponderLocationStatus.PICKEDUP.name().equals(status)) {
            mission.status(MissionStatus.UPDATED);
            return eventSink.missionPickedUp(mission).map(v -> mission);
        } else if (ResponderLocationStatus.DROPPED.name().equals(status)) {
            mission.status(MissionStatus.COMPLETED);
            return eventSink.missionCompleted(mission).map(v -> mission);
        } else {
            //do nothing
            return Uni.createFrom().item(mission);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private JsonObject getLocationUpdate(Message<String> message) {
        Optional<IncomingCloudEventMetadata> metadata = message.getMetadata(IncomingCloudEventMetadata.class);
        if (metadata.isEmpty()) {
            log.warn("Incoming message is not a CloudEvent");
            return null;
        }
        IncomingCloudEventMetadata<String> cloudEventMetadata = metadata.get();
        String dataContentType = cloudEventMetadata.getDataContentType().orElse("");
        if (!dataContentType.equalsIgnoreCase("application/json")) {
            log.warn("CloudEvent data content type is not specified or not 'application/json'. Message is ignored");
            return null;
        }
        String type = cloudEventMetadata.getType();
        if (!(Arrays.asList(ACCEPTED_MESSAGE_TYPES).contains(type))) {
            log.debug("CloudEvent with type '" + type + "' is ignored");
            return null;
        }
        try {
            JsonObject json = new JsonObject(message.getPayload());
            if (json.getString("responderId") == null || json.getString("responderId").isBlank()
                    || json.getString("missionId") == null || json.getString("missionId").isBlank()
                    || json.getString("incidentId") == null || json.getString("incidentId").isBlank()
                    || json.getString("status") == null || json.getString("status").isBlank()
                    || json.getDouble("lat") == null || json.getDouble("lon") == null
                    || json.getBoolean("human") == null || json.getBoolean("continue") == null) {
                log.warn("Unexpected message structure. Message is ignored");
                return null;
            }
            log.debug("Processing message: " + json.toString());
            return json;
        } catch (Exception e) {
            log.warn("Unexpected message structure. Message is ignored");
            return null;
        }
    }

    private String getKey(JsonObject json) {
        return json.getString("incidentId") + ":" + json.getString("responderId");
    }

}
