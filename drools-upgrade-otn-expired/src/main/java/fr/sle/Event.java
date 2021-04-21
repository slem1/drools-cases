package fr.sle;

import java.util.Date;

public abstract class Event {

    public Event(Date eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }

    private Date eventTimestamp;

    public Date getEventTimestamp() {
        return eventTimestamp;
    }

    public void setEventTimestamp(Date eventTimestamp) {
        this.eventTimestamp = eventTimestamp;
    }
}
