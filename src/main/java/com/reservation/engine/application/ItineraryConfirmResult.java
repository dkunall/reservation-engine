package com.reservation.engine.application;

public record ItineraryConfirmResult(ItineraryView itinerary, ConfirmResult.Outcome outcome) {
}
