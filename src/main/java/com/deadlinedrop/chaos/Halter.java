package com.deadlinedrop.chaos;

/**
 * Stops this instance abruptly, the way a crash would: no shutdown hooks, no draining, no
 * goodbye to the database. Behind an interface so tests can count calls instead of dying.
 */
public interface Halter {

    void halt();
}
