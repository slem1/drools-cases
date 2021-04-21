package fr.sle;

import java.util.Date;
import java.util.Objects;

public class BEvent extends Event {

    private String id;

    public BEvent(Date eventTimestamp, String id) {
        super(eventTimestamp);
        this.id = id;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        BEvent bEvent = (BEvent) o;
        return Objects.equals(id, bEvent.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "BEvent{" +
                "id='" + id + '\'' +
                '}';
    }
}
