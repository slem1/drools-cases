package fr.sle;

import java.util.Date;
import java.util.Objects;

public class AEvent extends Event{

    private String name;

    public AEvent(Date eventTimestamp, String name) {
        super(eventTimestamp);
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        AEvent aEvent = (AEvent) o;
        return Objects.equals(name, aEvent.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }

    @Override
    public String toString() {
        return "AEvent{" +
                "name='" + name + '\'' +
                '}';
    }
}
