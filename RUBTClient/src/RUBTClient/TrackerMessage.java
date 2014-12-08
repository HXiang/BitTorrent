package RUBTClient;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;

import GivenTools.Bencoder2;
import GivenTools.BencodingException;

public class TrackerMessage {
	

	public static final ByteBuffer KEY_FAILURE = ByteBuffer.wrap(new byte[] {'f', 'a', 'i', 'l', 'u', 'r', 'e', ' ', 'r', 'e', 'a', 's', 'o','n' });
	public static final ByteBuffer KEY_COMPLETE = ByteBuffer.wrap(new byte[] {'c', 'o', 'm', 'p', 'l', 'e', 't', 'e' });
	public static final ByteBuffer KEY_INCOMPLETE = ByteBuffer.wrap(new byte[] {'i', 'n', 'c', 'o', 'm', 'p', 'l', 'e', 't', 'e' });
	public static final ByteBuffer KEY_INTERVAL = ByteBuffer.wrap(new byte[] {'i', 'n', 't', 'e', 'r', 'v', 'a', 'l' });
	public static final ByteBuffer KEY_MIN_INTERVAL = ByteBuffer.wrap(new byte[] { 'm', 'i', 'n', ' ', 'i', 'n', 't', 'e', 'r', 'v', 'a', 'l' });
	public static final ByteBuffer KEY_PEERS = ByteBuffer.wrap(new byte[] {'p', 'e', 'e', 'r', 's' });
	public static final ByteBuffer KEY_PEER_IP = ByteBuffer.wrap(new byte[] {'i', 'p'});
	public static final ByteBuffer KEY_PEER_ID = ByteBuffer.wrap(new byte[] {'p', 'e', 'e', 'r', ' ', 'i', 'd'});
	public static final ByteBuffer KEY_PEER_PORT = ByteBuffer.wrap(new byte[] {'p', 'o', 'r', 't'});
	
	public String failure;
	public int complete;
	public int incomplete;
	public int interval;
	public int minInterval;
	public ArrayList<Peer> peerList = new ArrayList<Peer>();
	
	
	public TrackerMessage(byte[] trackerMessage) {
		
		HashMap<ByteBuffer, Object> message = null; 
		
		try {
			message = (HashMap<ByteBuffer, Object>) Bencoder2.decode(trackerMessage);
		} catch (BencodingException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		if(message.containsKey(KEY_FAILURE)) {
			System.out.println("Error connecting to tracker");
			System.exit(0);
		}
		
		if(message.containsKey(TrackerMessage.KEY_COMPLETE)) {
			this.complete = (Integer) message.get(KEY_COMPLETE);
		}
		
		if(message.containsKey(KEY_INCOMPLETE)) {
			this.incomplete = (Integer) message.get(KEY_INCOMPLETE);
		}
		
		if(message.containsKey(KEY_INTERVAL)) {
			this.interval = (Integer) message.get(KEY_INTERVAL);
		}
		
		if(message.containsKey(KEY_MIN_INTERVAL)) {
			this.minInterval = (Integer) message.get(KEY_MIN_INTERVAL);
		}
		
		if(message.containsKey(KEY_PEERS)) {
			
			ArrayList<HashMap<ByteBuffer, Object>> peers = (ArrayList<HashMap<ByteBuffer, Object>>) message.get(KEY_PEERS);
			for(HashMap<ByteBuffer, Object> pm : peers){
				
				String pIP = null;
				String pID = null;
				int pPort = 0;
				
				if(pm.containsKey(KEY_PEER_ID)) {
					ByteBuffer peerIDBytes = (ByteBuffer) pm.get(KEY_PEER_ID);
					pID = new String(peerIDBytes.array());
				}
				if(pm.containsKey(KEY_PEER_IP)) {
					ByteBuffer peerIPBytes = (ByteBuffer) pm.get(KEY_PEER_IP);
					pIP = new String(peerIPBytes.array());
				}
				if(pm.containsKey(KEY_PEER_PORT)) {
					pPort = (Integer)pm.get(KEY_PEER_PORT);
				}
				
				this.peerList.add(new Peer(pPort, pIP, pID));
			}
		}
		
		
	}
	
	
	
	
	
	
	
	
}
