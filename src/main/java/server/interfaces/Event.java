package server.interfaces;

public interface Event {

	long getEventTime();
	int compareTo(Event o);
}
