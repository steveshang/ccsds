/*
 * Copyright 2018-2019 Dario Lucia (https://www.dariolucia.eu)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package eu.dariolucia.ccsds.tmtc.datalink.channel.sender.mux;

import eu.dariolucia.ccsds.tmtc.datalink.channel.sender.AbstractSenderVirtualChannel;
import eu.dariolucia.ccsds.tmtc.datalink.pdu.TmTransferFrame;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * This class implements a simple master channel muxer for the various virtual channels. It provides master channel
 * frame number to the virtual channels and makes sure that the generated frames go out from the muxer in the order
 * imposed by the master channel frame counter.
 *
 * This class implements the Consumer interface, so that it can be used to receive TM frames from any stream of
 * TmTransferFrame objects. In addition, this class implements the IVirtualChannelSenderOutput, so objects of this class
 * can be registered to a set of virtual channels and provide mux capabilities.
 *
 * This class does not implement any load balancing across the registered virtual channels (i.e. priorities), however it
 * makes sure that no virtual channel suffers from starvation, i.e. a generated frame from a low rate virtual channel
 * will be transmitted according to the master channel frame counter, which is assigned at frame generation time. Method
 * getNextCounter can be used as a Supplier method for the provision of such frame counter. Frames that have a higher
 * master channel frame counter than the expected one will be buffered and transmitted at the correct time and in the
 * correct order.
 *
 * This class is thread safe.
 */
public class TmMasterChannelMuxer extends SimpleMuxer<TmTransferFrame> {

	private final AtomicInteger masterChannelCounter = new AtomicInteger(0);
	private final AtomicInteger expectedCounter = new AtomicInteger(0);
	protected final List<TmTransferFrame> reorderingBuffer = new LinkedList<>();
	protected final Set<Short> pendingVcs = new TreeSet<>();

	/**
	 * Create a new TM Master Channel Muxer, which forwards received frames to the provided sink.
	 *
	 * @param output the sink for received TM frames.
	 */
	public TmMasterChannelMuxer(Consumer<TmTransferFrame> output) {
		super(output);
	}

	/**
	 * This method sets the counter for the next expected TM frame to transmit to the sink. If greater than 256, the
	 * method will assign its mod 256 to the internal variable.
	 *
	 * @param expectedCounter the expected master channel frame counter
	 */
	public synchronized void setExpectedCounter(int expectedCounter) {
		this.expectedCounter.set(expectedCounter % 256);
	}

	/**
	 * This method sets the counter for the next generated TM frame, requested through the getNextCounter. If greater
	 * than 256, the method will assign it mod 256 to the internal variable.
	 *
	 * @param masterChannelCounter the next returned master channel frame counter
	 */
	public synchronized void setMasterChannelCounter(int masterChannelCounter) {
		this.masterChannelCounter.set(masterChannelCounter % 256);
	}

	/**
	 * This method returns the next counter and increments (mod 256) the internal variable.
	 *
	 * @return the next master channel frame counter
	 */
	public synchronized int getNextCounter() {
		int toReturn = this.masterChannelCounter.getAndIncrement();
		if(this.masterChannelCounter.get() % 256 == 0) {
			this.masterChannelCounter.set(0);
		}
		return toReturn;
	}

	/**
	 * This method forwards the TM frame to the sink and then tries to empty the internal buffer, in case of pending
	 * TM frames. If the TM frame cannot be immediately forwarded, it is added to the internal buffer for later
	 * forwarding. If in the buffer there is already another frame generated by the same virtual channel, then the
	 * calling thread will block until the frame is finally forwarded. If this approach is not suitable for the
	 * application, then this class can be subclassed and this method rewritten to implement the desired approach.
	 *
	 * @param tmTransferFrame the TM frame to forward to the sink or to be put in the internal buffer
	 */
	@Override
	public synchronized void accept(TmTransferFrame tmTransferFrame) {
		// If the transfer frame is the one you expect, then if will be forwarded by tryForwarding. Otherwise...
		if (!tryForwarding(tmTransferFrame)) {
			// If there is another frame from the same VC
			while(this.pendingVcs.contains(tmTransferFrame.getVirtualChannelId())) {
				// Wait for the queue to be free and put the frame in the queue
				try {
					wait();
				} catch (InterruptedException e) {
					// Stop the execution here
					return;
				}
			}
			// Maybe now it works again, try again
			if(!tryForwarding(tmTransferFrame)) {
				// Then no way, put the transfer frame in the queue
				this.reorderingBuffer.add(tmTransferFrame);
				this.pendingVcs.add(tmTransferFrame.getVirtualChannelId());
				// And notify
				notifyAll();
			}
		}
	}

	private synchronized boolean tryForwarding(TmTransferFrame tmTransferFrame) {
		if (tmTransferFrame.getMasterChannelFrameCount() == this.expectedCounter.get()) {
			// Forward it to the output
			this.output.accept(tmTransferFrame);
			// Increment the expected counter
			int val = this.expectedCounter.incrementAndGet();
			if(val % 256 == 0) {
				this.expectedCounter.set(0);
			}
			// Forward all the other frames that you can
			boolean moreFramesToForward = !this.reorderingBuffer.isEmpty();
			while(moreFramesToForward) {
				moreFramesToForward = false;
				for(Iterator<TmTransferFrame> it = this.reorderingBuffer.iterator(); it.hasNext();) {
					TmTransferFrame tf = it.next();
					if(tf.getMasterChannelFrameCount() == this.expectedCounter.get()) {
						// Remove from list
						it.remove();
						// Remove from set
						this.pendingVcs.remove(tf.getVirtualChannelId());
						// Forward it to the output
						this.output.accept(tf);
						// Increment the expected counter
						val = this.expectedCounter.incrementAndGet();
						if(val % 256 == 0) {
							this.expectedCounter.set(0);
						}
						// Maybe you have another one?
						moreFramesToForward = !this.reorderingBuffer.isEmpty();
					}
				}
			}
			notifyAll();
			return true;
		}
		return false;
	}

	/**
	 * This method forwards the generated frame to the apply method. The virtual channel object and the bufferedBytes
	 * value are ignored.
	 *
	 * @param vc The virtual channel that generated the frame
	 * @param generatedFrame The received frame
	 * @param bufferedBytes the number of bytes still in the virtual channel buffer
	 */
	@Override
	public void transferFrameGenerated(AbstractSenderVirtualChannel<TmTransferFrame> vc, TmTransferFrame generatedFrame, int bufferedBytes) {
		accept(generatedFrame);
	}
}
