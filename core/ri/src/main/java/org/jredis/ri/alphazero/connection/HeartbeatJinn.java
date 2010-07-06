/*
 *   Copyright 2009 Joubin Houshyar
 * 
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *    
 *   http://www.apache.org/licenses/LICENSE-2.0
 *    
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

package org.jredis.ri.alphazero.connection;

import java.util.concurrent.atomic.AtomicBoolean;
import org.jredis.connector.Connection;
import org.jredis.connector.ConnectionSpec;
import org.jredis.connector.Connection.Event;
import org.jredis.connector.Connection.Modality;
import org.jredis.protocol.Command;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A demon thread tasked with PINGing the associated connection
 * per its {@link ConnectionSpec#getHeartbeat()} heartbeat interval.
 * <p>
 * {@link HeartbeatJinn}s are {@link Connection.Listener}s, and rely
 * on {@link Connection} event propagation to synchronize their activity
 * with the associated connection.
 * <p>
 * The connection must only call {@link Thread#start()} when it has
 * established its connectivity.
 * 
 * @author  Joubin Houshyar (alphazero@sensesay.net)
 * @version alpha.0, Nov 22, 2009
 * @since   alpha.0
 * 
 */
public class HeartbeatJinn extends Thread implements Connection.Listener{
    private static Logger logger = LoggerFactory.getLogger(HeartbeatJinn.class);

	/**  */
	AtomicBoolean connected;
	/**  */
	AtomicBoolean mustBeat;
	/**  */
	private final Modality modality;
	/**  */
	private final Connection conn;
	/**  */
	private final int period;
	
	/**
	 * Instantiate and initialize the HeartbeatJinn.  On return, this instance
	 * is:
	 * <li> sets flag to work
	 * <li> added to the listeners for the connection
	 * <li> assumes connection is not yet established
	 * 
	 * @param conn associated with this instnace
	 * @param periodInSecs a reasonable value is 1.  Internally converted to millisecs.
	 * @param name associated with this (heartbeat) thread.
	 */
	public HeartbeatJinn (Connection conn, int periodInSecs, String name) {
		super (name);
		setDaemon(true);
		this.conn = conn;
		conn.addListener(this);
		this.modality = conn.getModality();
		this.period = periodInSecs * 1000;
		this.connected = new AtomicBoolean(false);
		this.mustBeat = new AtomicBoolean(true);
	}

	/**
	 * 
	 */
	public void exit() {
		mustBeat.set(false);
		this.interrupt();
	}
	
	// ------------------------------------------------------------------------
	// INTERFACE
	/* ====================================================== Thread (Runnable)
	 * 
	 */
	// ------------------------------------------------------------------------
	
	/**
	 * Your basic infinite loop with branchings on connection state and modality
	 * <p>
	 * TODO: run loop should be a proper state machine.
	 * TODO: delouse this baby ..
	 * 
	 * @see java.lang.Thread#run()
	 */
	public void run () {
		logger.info("HeartbeatJinn thread <%s> started.", Thread.currentThread().getName());
		while (mustBeat.get()) {
			try {
				if(connected.get()){  // << buggy.
					try {
						switch (modality){
						case Asynchronous:
							conn.queueRequest(Command.PING);
							break;
						case Synchronous:
							conn.serviceRequest(Command.PING);
							break;
						}
					}
					catch (Exception e) {
						// addressing buggy above.  notifyDisconnected gets called after we have checked it but before we
						// made the call - it is disconnected by the time the call is made and we end up here
						// checking the flag again and if it is indeed not the above scenario then there is something wrong,
						// otherwise ignore it and basically loop on sleep until we get notify on connect again (if ever).
						if(connected.get()){
							// how now brown cow?  we'll log it for now and assume reconnect try in progress and wait for the flag change.
							logger.warn("HeartbeatJinn thread <" + Thread.currentThread().getName() + "> encountered exception on PING: " + e.getMessage() );
							connected.set(false);
						}
					}
				}
				sleep (period);	// sleep regardless - 
			}
			catch (InterruptedException e) { 
				logger.info("HeartbeatJinn thread <%s> interrupted.", Thread.currentThread().getName());
				break; 
			}
		}
		logger.info("HeartbeatJinn thread <%s> stopped.", Thread.currentThread().getName());
	}

	// ------------------------------------------------------------------------
	// INTERFACE
	/* =================================================== Connection.Listener
	 * 
	 * hooks for integrating the heartbeat thread's state with the associated
	 * connection's state through event callbacks. 
	 */
	// ------------------------------------------------------------------------
	/**
	 * 
     * @see org.jredis.connector.Connection.Listener#onEvent(org.jredis.connector.Connection.Event)
     */
    public void onEvent (Event event) {
    	switch (event.getType()){
			case CONNECTED:
//				System.out.println ("********** GOT    CONNECTED EVENT CALLBACK **************");
				connected.set(true);
				break;
			case DISCONNECTED:
//				System.out.println ("********** GOT DISCONNECTED EVENT CALLBACK **************");
				connected.set(false);
				break;
			case FAULTED:
//				System.out.println ("********** GOT      FAULTED EVENT CALLBACK **************");
				exit();
//				mustBeat.set(false);
				break;
    	}
    }
}
