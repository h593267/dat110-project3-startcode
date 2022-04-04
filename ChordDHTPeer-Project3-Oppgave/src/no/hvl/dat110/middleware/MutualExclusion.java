/**
 * 
 */
package no.hvl.dat110.middleware;

import java.math.BigInteger;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import no.hvl.dat110.rpc.interfaces.NodeInterface;
import no.hvl.dat110.util.LamportClock;
import no.hvl.dat110.util.Util;

/**
 * @author tdoy
 *
 */
public class MutualExclusion {

	/** lock variables */
	private boolean CS_BUSY = false; // indicate to be in critical section (accessing a shared resource)
	private boolean WANTS_TO_ENTER_CS = false; // indicate to want to enter CS
	private List<Message> queueack; // queue for acknowledged messages
	private List<Message> mutexqueue; // queue for storing process that are denied permission. We really don't need
										// this for quorum-protocol

	private LamportClock clock; // lamport clock
	private Node node;

	public MutualExclusion(Node node) throws RemoteException {
		this.node = node;

		clock = new LamportClock();
		queueack = new ArrayList<Message>();
		mutexqueue = new ArrayList<Message>();
	}

	public void acquireLock() {
		CS_BUSY = true;
	}

	public void releaseLocks() {
		WANTS_TO_ENTER_CS = false;
		CS_BUSY = false;
	}

	public boolean doMutexRequest(Message message, byte[] updates) throws RemoteException {

		System.out.println(node.nodename + " wants to access CS");

		// clear the queueack before requesting for votes
		queueack.clear();
		// clear the mutexqueue
		mutexqueue.clear();
		// increment clock
		clock.increment();
		// adjust the clock on the message, by calling the setClock on the message
		message.setClock(clock.getClock());
		// wants to access resource - set the appropriate lock variable
		WANTS_TO_ENTER_CS = true;

		// start MutualExclusion algorithm
		// first, removeDuplicatePeersBeforeVoting. A peer can contain 2 replicas of a
		// file. This peer will appear twice
		List<Message> activenodes = removeDuplicatePeersBeforeVoting();
		// multicast the message to activenodes (hint: use multicastMessage)
		multicastMessage(message, activenodes); // queueack???
		// check that all replicas have replied (permission)
		// if yes, acquireLock

		boolean permission = areAllMessagesReturned(activenodes.size());

		// if yes, acquireLock
		if (permission) {
			acquireLock();

			// node.broadcastUpdatetoPeers
			node.broadcastUpdatetoPeers(updates);

			// clear the mutexqueue
			mutexqueue.clear();
		}

		// return permission
		return permission;
	}

	// multicast message to other processes including self
	private void multicastMessage(Message message, List<Message> activenodes) throws RemoteException {

		// iterate over the activenodes

		// obtain a stub for each node from the registry

		// call onMutexRequestReceived()

		activenodes.stream().forEach(a -> {
			try {
				Util.getProcessStub(a.getNodeIP(), a.getPort()).onMutexRequestReceived(message);
			} catch (RemoteException e) {
				e.printStackTrace();
			}
		});

	}

	public void onMutexRequestReceived(Message message) throws RemoteException {

		// increment the local clock
		clock.increment();

		// if message is from self, acknowledge, and call
		// onMutexAcknowledgementReceived()
		if (message.getNodeIP().equals(node.getNodeName())) {
			message.setAcknowledged(true);
			onMutexAcknowledgementReceived(message);
		}

		int caseid = -1;

		// write if statement to transition to the correct caseid
		// caseid=0: Receiver is not accessing shared resource and does not want to
		// (send OK to sender)
		// caseid=1: Receiver already has access to the resource (dont reply but queue
		// the request)
		// caseid=2: Receiver wants to access resource but is yet to - compare own
		// message clock to received message's clock

		if (!CS_BUSY && !WANTS_TO_ENTER_CS) {
			caseid = 0;
		} else if (CS_BUSY) {
			caseid = 1;
		} else if (WANTS_TO_ENTER_CS) {
			caseid = 2;
		}

		// check for decision
		doDecisionAlgorithm(message, mutexqueue, caseid);
	}

	public void doDecisionAlgorithm(Message message, List<Message> queue, int condition) throws RemoteException {

		String procName = message.getNodeIP(); // this is the same as nodeName in the Node class
		int port = message.getPort(); // port on which the registry for this stub is listening

//		NodeInterface node = Util.getProcessStub(procName, port);

		switch (condition) {

		/**
		 * case 1: Receiver is not accessing shared resource and does not want to (send
		 * OK to sender)
		 */
		case 0: {
			// get a stub for the sender from the registry
			// acknowledge message
			// send acknowledgement back by calling onMutexAcknowledgementReceived()
			Util.getProcessStub(procName, port).onMutexAcknowledgementReceived(message);

			message.setAcknowledged(true);

			System.out.println("case 0");

			break;
		}

		/**
		 * case 2: Receiver already has access to the resource (dont reply but queue the
		 * request)
		 */
		case 1: {

			// queue this message

			queue.add(message);

			System.out.println("case 1");

			break;
		}

		/**
		 * case 3: Receiver wants to access resource but is yet to (compare own message
		 * clock to received message's clock the message with lower timestamp wins) -
		 * send OK if received is lower. Queue message if received is higher
		 */
		case 2: {
			// check the clock of the sending process
			// own clock for the multicast message
			// compare clocks, the lowest wins
			// if clocks are the same, compare nodeIDs, the lowest wins
			// if sender wins, acknowledge the message, obtain a stub and call
			// onMutexAcknowledgementReceived()
			// if sender looses, queue it

			if (message.getClock() == node.getMessage().getClock()) {

				if (message.getNodeID().compareTo(node.getNodeID()) < 0) {
					message.setAcknowledged(true);
					Util.getProcessStub(procName, port).onMutexAcknowledgementReceived(message);
				} else
					queue.add(message);

			} else if (node.getMessage().getClock() < message.getClock()) {
				queue.add(message);
			} else if (message.getClock() < node.getMessage().getClock()) {
				message.setAcknowledged(true);
				Util.getProcessStub(procName, port).onMutexAcknowledgementReceived(message);
			}

			System.out.println("case 2");

			break;
		}

		default:
			break;
		}

	}

	public void onMutexAcknowledgementReceived(Message message) throws RemoteException {

		// add message to queueack

		queueack.add(message);

	}

	// multicast release locks message to other processes including self
	public void multicastReleaseLocks(Set<Message> activenodes) {

		// iterate over the activenodes

		// obtain a stub for each node from the registry

		// call releaseLocks()

		for (Message m : activenodes) {
			NodeInterface nodeInterface = Util.getProcessStub(m.getNodeIP(), m.getPort());
			try {
				nodeInterface.releaseLocks();
			} catch (RemoteException e) {
				e.printStackTrace();
			}
			;
		}

	}

	private boolean areAllMessagesReturned(int numvoters) throws RemoteException {
		// check if the size of the queueack is same as the numvoters
		boolean sameSize = queueack.size() == numvoters;

		// clear the queueack
		queueack.clear();

		// return true if yes and false if no

		return sameSize;
	}

	private List<Message> removeDuplicatePeersBeforeVoting() {

		List<Message> uniquepeer = new ArrayList<Message>();
		for (Message p : node.activenodesforfile) {
			boolean found = false;
			for (Message p1 : uniquepeer) {
				if (p.getNodeIP().equals(p1.getNodeIP())) {
					found = true;
					break;
				}
			}
			if (!found)
				uniquepeer.add(p);
		}
		return uniquepeer;
	}
}
