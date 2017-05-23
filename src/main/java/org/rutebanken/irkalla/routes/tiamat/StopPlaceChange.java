package org.rutebanken.irkalla.routes.tiamat;

import com.google.common.base.Joiner;
import org.rutebanken.irkalla.domain.CrudAction;
import org.rutebanken.irkalla.routes.tiamat.graphql.model.StopPlace;
import org.rutebanken.irkalla.routes.tiamat.graphql.model.ValidBetween;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Representation of a change for a stop place.
 */
public class StopPlaceChange {
    public enum StopPlaceUpdateType {NAME, COORDINATES, TYPE, NEW_QUAY, REMOVED_QUAY, MINOR, MAJOR}

    private CrudAction crudAction;

    private StopPlaceUpdateType updateType;

    private StopPlace current;

    private StopPlace previousVersion;

    private List<String> oldValue = new ArrayList<>();

    private List<String> newValue = new ArrayList<>();

    public StopPlaceChange(CrudAction crudAction, StopPlace current, StopPlace previousVersion) {
        this.crudAction = crudAction;
        this.current = current;
        this.previousVersion = previousVersion;
        detectUpdateType();
    }


    public Instant getChangeTime() {
        if (CollectionUtils.isEmpty(current.validBetweens)) {
            return null;
        }

        ValidBetween currentValidBetween = current.validBetweens.get(0);

        if (CrudAction.REMOVE.equals(crudAction)) {
            return currentValidBetween.toDate;
        }
        return currentValidBetween.fromDate;
    }


    public CrudAction getCrudAction() {
        return crudAction;
    }

    public StopPlace getCurrent() {
        return current;
    }

    public StopPlace getPreviousVersion() {
        return previousVersion;
    }


    public StopPlaceUpdateType getUpdateType() {
        return updateType;
    }

    public String getOldValue() {
        return oldValue.isEmpty() ? null : Joiner.on("\n").join(oldValue);
    }

    public String getNewValue() {
        return newValue.isEmpty() ? null : Joiner.on("\n").join(newValue);
    }

    private void detectUpdateType() {
        if (!CrudAction.UPDATE.equals(crudAction)) {
            return;
        }

        // Defaulting to minor if no substantial changes are found
        updateType = StopPlaceUpdateType.MINOR;

        checkForChanges(current.getNameAsString(), previousVersion.getNameAsString(), StopPlaceUpdateType.NAME);
        checkForChanges(current.stopPlaceType, previousVersion.stopPlaceType, StopPlaceUpdateType.TYPE);

        // TODO do we need to verify magnitude of coord change? Seems to be small changes due to rounding.
        checkForChanges(current.geometry, previousVersion.geometry, StopPlaceUpdateType.COORDINATES);

        List<String> currentQuayIds = current.safeGetQuays().stream().map(q -> q.id).collect(Collectors.toList());
        List<String> previousVersionQuayIds = previousVersion.safeGetQuays().stream().map(q -> q.id).collect(Collectors.toList());


        List<String> newQuays = currentQuayIds.stream().filter(q -> !previousVersionQuayIds.contains(q)).collect(Collectors.toList());
        List<String> removedQuays = previousVersionQuayIds.stream().filter(q -> !currentQuayIds.contains(q)).collect(Collectors.toList());

        if (!newQuays.isEmpty()) {
            registerUpdate(StopPlaceUpdateType.NEW_QUAY);
            newValue.add(newQuays.toString());
            oldValue.add(removedQuays.toString());
        } else if (!removedQuays.isEmpty()) {
            registerUpdate(StopPlaceUpdateType.REMOVED_QUAY);
            oldValue.add(removedQuays.toString());
        }

    }

    private void checkForChanges(Object curr, Object pv, StopPlaceUpdateType updateType) {
        if (!Objects.equals(curr, pv)) {
            registerUpdate(updateType);
            oldValue.add(ObjectUtils.nullSafeToString(pv));
            newValue.add(ObjectUtils.nullSafeToString(curr));
        }
    }

    private void registerUpdate(StopPlaceUpdateType updateType) {
        if (StopPlaceUpdateType.MINOR.equals(this.updateType)) {
            this.updateType = updateType;
        } else {
            // Set as MAJOR if multiple substantial changes are detected
            this.updateType = StopPlaceUpdateType.MAJOR;
        }
    }

}