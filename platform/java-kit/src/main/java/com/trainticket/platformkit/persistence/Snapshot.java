package com.trainticket.platformkit.persistence;

public record Snapshot<T>(String id, long version, T data) {
}
