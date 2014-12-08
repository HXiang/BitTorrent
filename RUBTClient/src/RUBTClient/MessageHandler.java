/**
 * @author Raymond Sun
 * @author Henry Xiang
 */

package RUBTClient;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.ByteBuffer;

public class MessageHandler {
	public final static int KEEP_ALIVE = -1;
	public final static int CHOKE = 0;
	public final static int UNCHOKE = 1;
	public final static int INTERESTED = 2;
	public final static int UNINTERESTED = 3;
	public final static int HAVE = 4;
	public final static int BITFIELD = 5;
	public final static int REQUEST = 6;
	public final static int PIECE = 7;
	public final static int CANCEL = 8;
	Socket connection;
	OutputStream outgoing;
	InputStream incoming;
	ByteBuffer messageOutgoing;
	ByteBuffer messageIncoming;
	public MessageHandler(Socket c) throws IOException {
		connection = c;
		outgoing = c.getOutputStream();
		incoming = c.getInputStream();
	}
	public void sendMessage(int type) throws IOException {
		sendMessage(type, null);
	}
	public void sendMessage(int type, byte[] payload) throws IOException{
		//Does not allow messages outside the base BitTorrent messages, -1 represents KEEPALIVE
		if(type < -1 || type > 8) {
			System.out.println("Invalid message type.");
			return;
		}
		//Special exception because only KEEPALIVE is length 0
		if(type == KEEP_ALIVE) {
			messageOutgoing = ByteBuffer.allocate(4);
			messageOutgoing.putInt(0);
			outgoing.write(messageOutgoing.array());
			System.out.println("Sent keepalive.");
			return;
		}
		//Length 1 messages
		if(payload == null) {
			messageOutgoing = ByteBuffer.allocate(5);
			messageOutgoing.putInt(1);
			messageOutgoing.put((byte) type);
			System.out.println();
			outgoing.write(messageOutgoing.array());
			return;
		}
		//Calculate payload length, stores as big endian, glues everything together, sends message
		messageOutgoing = ByteBuffer.allocate(5+payload.length);
		messageOutgoing.putInt(payload.length+1);
		messageOutgoing.put((byte)type);
		messageOutgoing.put(payload);
		System.out.print("Sent message: ");
		for(byte b:messageOutgoing.array())
			System.out.print("["+b+"]");
		System.out.println();
		outgoing.write(messageOutgoing.array());
	}
	//Calculates length using first 4 bytes, stores entire message as byte array
	public ByteBuffer receiveMessage() throws IOException{
		messageIncoming = ByteBuffer.allocate(4);
		int size = incoming.read(messageIncoming.array());
		if(size < 4) {
			messageIncoming = ByteBuffer.allocate(1);
			messageIncoming.put((byte)-2);
			return messageIncoming;
		}
		int length = messageIncoming.getInt();
		if(length == 0) {
			messageIncoming = ByteBuffer.allocate(1);
			messageIncoming.put((byte)-1);
			return messageIncoming;
		}
		messageIncoming = ByteBuffer.allocate(length);
		int offset = 0;
		//Continues looping until entire message has been stored (used for pieces)
		while(offset < length) {
			offset += incoming.read(messageIncoming.array(), offset, length - offset);
		}
		System.out.println("Received message: "+messageIncoming.get(0));
		return messageIncoming;


	}
	public void close() {
		try {
			connection.close();
		} catch (IOException e) {
			System.out.println("Could not close connection (really?)");
			e.printStackTrace();
		}
	}
}
