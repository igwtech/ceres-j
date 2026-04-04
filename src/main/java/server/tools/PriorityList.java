package server.tools;

import server.interfaces.Event;

public class PriorityList {

	Event[] buf;
	int sizestep;
	int count = 0;
	
	public PriorityList(){
		this.sizestep = 8;
		buf = new Event[sizestep];
	}
	
	public PriorityList(int sizestep){
		this.sizestep = sizestep;
		buf = new Event[sizestep];
	}
	
	public void add(Event obj) {
		if (count == buf.length) {
			Event[] newbuf = new Event[buf.length + sizestep];
			System.arraycopy(buf, 0, newbuf, 0, count);
			buf = newbuf;
		}

		buf[count] = obj;
		toTopOrdering(count);
	
		count++;
	}

	private void toTopOrdering(int child) {
		if (child == 0) return;
		int parent = (child -1) /2;
		if (buf[child].compareTo(buf[parent]) < 0) {
			Event tmp = buf[child];
			buf[child] = buf[parent];
			buf[parent] = tmp;
			toTopOrdering(parent);
		}
	}

	public boolean isEmpty() {
		return count == 0;
	}

	public Event removeFirst() {
		if (count == 0) return null;
		Event first = buf[0];
		count--;
		if (count > 0) {
			buf[0] = buf[count];
			toBottomOrdering(0);
		}
		return first;
	}

	private void toBottomOrdering(int parent) {
		int child1 = parent * 2 + 1;
		int child2 = child1 + 1;
		if (child1 >= count) return;
		int child = child1;
		if (child2 < count) {
			if (buf[child1].compareTo(buf[child2]) > 0) {
				child = child2;
			}
		}
		if (buf[child].compareTo(buf[parent]) < 0) {
			Event tmp = buf[child];
			buf[child] = buf[parent];
			buf[parent] = tmp;
			toBottomOrdering(child);
		}
	}

	public Event getFirst() {
		if (count == 0) return null;
		return buf[0];
	}

}
