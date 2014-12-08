/**
 * @author Raymond Sun
 * @author Henry Xiang
 */

package RUBTClient;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Random;
import java.util.Scanner;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;

import GivenTools.BencodingException;
import GivenTools.TorrentInfo;

//The complete program thus far.
public class RUBTClient {
	static TorrentInfo torrentInfo;
	static ByteBuffer[] pieceData;
	static boolean[] piecesCompleted;
	static int[] pieceCompletionIndex;
	static LinkedBlockingQueue<Integer> pieceAccess;
	static LinkedBlockingQueue<Socket> connectionsWaiting;
	static LinkedBlockingQueue<Socket> connectionsEstablished;
	static ServerSocket server;
	static String ourID;
	static boolean startedDownloading;
	static LinkedBlockingQueue<Integer> trackerAccess;
	static boolean programEnd;
	static int downloaded;
	static int uploaded;
	static int minInterval;

	private static class SocketSpawner implements Runnable {

		@Override
		public void run() {
			try{
				server = ServerSocketFactory.getDefault().createServerSocket(6969);
				while(true) {
					Socket clientSocket = server.accept();
					System.out.println("Accepted connection.");
					connectionsWaiting.add(clientSocket);
				}
			} catch (Exception e) {
				System.out.println("Server connection has closed.");

			}

		}

	}

	private static class HandshakeEstablisher implements Runnable {
		private Socket peerConnection;
		private HandshakeEstablisher() {

		}
		@Override
		public void run() {
			ByteBuffer info_hash = torrentInfo.info_hash;
			try{
				peerConnection = connectionsWaiting.take();
			}catch(Exception e) {
				if(e instanceof SocketTimeoutException) {
					System.out.println("Peer did not send handshake.");
				} else {
					System.err.println("Handshake failed.");
					e.printStackTrace();
				}
			}
			while(peerConnection.getPort() != 8888) {
				try {
					peerConnection.setSoTimeout(10000);
					System.out.println("Handshaking.");


					InputStream bytesIncoming = peerConnection.getInputStream();
					OutputStream bytesOutgoing = peerConnection.getOutputStream();

					ByteBuffer handshake = ByteBuffer.allocate(68);
					handshake.put((byte) 19);
					handshake.put(new byte[]
							{ 'B', 'i', 't', 'T', 'o', 'r', 'r', 'e', 'n', 't', ' ', 'p', 'r', 'o', 't', 'o', 'c', 'o', 'l' });

					byte[] reserved = new byte[8];
					for(int i = 0; i < 8; i++)
						reserved[i] = (byte)0;
					handshake.put(reserved);
					handshake.put(info_hash);
					for(int i = 0; i < ourID.length(); i++)
						handshake.put((byte)ourID.charAt(0));

					bytesOutgoing.write(handshake.array());

					ByteBuffer incHandshake = ByteBuffer.allocate(68);
					int offset = 0;
					while(offset < 68) {
						offset += bytesIncoming.read(incHandshake.array(), offset, 68 - offset);

					}
					byte[] reader = new byte[19];
					incHandshake.get();
					incHandshake.get(reader);
					reader = new byte[8];
					incHandshake.get(reader);
					reader = new byte[20];
					incHandshake.get(reader);
					if(!byteToHexStr(info_hash.array()).equals(byteToHexStr(reader))) {
						peerConnection.close();
						System.out.println(byteToHexStr(info_hash.array()));
						System.out.println(byteToHexStr(reader));
						System.out.println(peerConnection.getInetAddress()+": SHA-1 hashes did not match.");
						return;
					}
					incHandshake.get(reader);
					System.out.println("Handshake complete.");
					connectionsEstablished.put(peerConnection);

					
				}catch (Exception e) {
					if(e instanceof SocketTimeoutException) {
						System.out.println("Peer did not send handshake.");
					} else {
						System.err.println("Handshake failed.");
						e.printStackTrace();
					}
				}
				try {
					System.out.println();
					peerConnection = connectionsWaiting.take();
				} catch (InterruptedException e) {
					System.err.println("Interrupted.");
					e.printStackTrace();
				}
			}
			System.out.println("Handshake establisher closed.");

		}

	}

	private static class PeerConnectionSender implements Runnable {
		MessageHandler link;
		boolean peerIsChoking = true;
		boolean peerIsInterested = false;
		boolean weAreChoking = true;
		boolean weAreInterested = false;
		LinkedBlockingQueue<ByteBuffer> messagesIncoming;
		private PeerConnectionSender(MessageHandler l) {
			link = l;
			peerIsChoking = true;
			peerIsInterested = false;
			weAreChoking = true;
			weAreInterested = false;
			messagesIncoming = new LinkedBlockingQueue<ByteBuffer>();
		}
		@Override
		public void run() {

			new Thread(new PeerConnectionListener(link, messagesIncoming)).start();
			long timeSinceLast = System.currentTimeMillis();
			long timeSinceAnnounce = System.currentTimeMillis();
			ByteBuffer[] pieceHashes = torrentInfo.piece_hashes;
			ArrayList<Integer> piecesHeldByPeer = new ArrayList<Integer>();

			try {

				link.sendMessage(5,createBitfield());

			} catch (IOException e4) {
				System.err.println("Could not send bitfield.");
				e4.printStackTrace();
			}

			ByteBuffer message = null;
			int messageID = -1;
			outerloop:
			while(!programEnd) {
				try {
					trackerAccess.take();
					if(System.currentTimeMillis() - timeSinceAnnounce > 120000) {
						createGetRequest();
					}
					trackerAccess.put(1);
				} catch (InterruptedException e5) {

					System.out.println("Could not check tracker interval.");
					e5.printStackTrace();
				}

				try {
					pieceAccess.take();

					for(int i = piecesHeldByPeer.size() - 1; i >= 0; i--) {
						if(piecesCompleted[piecesHeldByPeer.get(i)]) {
							piecesHeldByPeer.remove(i);
						}
					}
					pieceAccess.add(1);
				} catch (Exception e) {
					if(e instanceof SocketException) {
						break;
					}
					System.err.println("Could not count pieces held.");
					e.printStackTrace();
				}
				if(piecesHeldByPeer.size() > 0 && !weAreInterested) {
					try {
						link.sendMessage(MessageHandler.INTERESTED);
					} catch (IOException e) {
						if(e instanceof SocketException) {
							break;
						}
						System.err.println("Problem connecting to peer.");
						e.printStackTrace();
					}
					weAreInterested = true;
					timeSinceLast = System.currentTimeMillis();
				}
				if(piecesHeldByPeer.size() == 0 && weAreInterested) {
					try {
						link.sendMessage(MessageHandler.UNINTERESTED);
					} catch (IOException e) {
						System.err.println("Problem connecting to peer.");
						e.printStackTrace();
					}
					weAreInterested = false;
					timeSinceLast = System.currentTimeMillis();
				}
				if(piecesHeldByPeer.size() > 0 && weAreInterested && !peerIsChoking) {  //creates request message, sends to peer
					try {
						pieceAccess.take();
						//System.out.println("Piece requested: "+piecesHeldByPeer.get(0));
						ByteBuffer requestBuffer = ByteBuffer.allocate(12);
						int thePiece = piecesHeldByPeer.get(0);
						requestBuffer.putInt(thePiece);
						requestBuffer.putInt(pieceCompletionIndex[thePiece]);
						int requestAmount = pieceData[thePiece].capacity();
						int amountRemaining = 0;
						for(boolean done:piecesCompleted) {
							if(done)
								amountRemaining += pieceData[thePiece].capacity(); //adding up everything that's done
						}
						amountRemaining = torrentInfo.file_length - amountRemaining; //calculating what there still is to download
						if(amountRemaining < requestAmount) { //if the amount remaining is less than the piece_length
							requestAmount = amountRemaining;
							pieceData[thePiece] = ByteBuffer.allocate(requestAmount);
						}
						requestBuffer.putInt(requestAmount);
						//System.out.println("Request amount: "+requestAmount);
						pieceAccess.add(1);
						timeSinceLast = System.currentTimeMillis();
						link.sendMessage(MessageHandler.REQUEST, requestBuffer.array());
					} catch (Exception e) {
						if(e instanceof SocketException) {
							break;
						}
						System.err.println("Problem downloading piece.");
						e.printStackTrace();
					}
				}

				try {
					message = messagesIncoming.poll(10, TimeUnit.SECONDS);
				} catch (InterruptedException e4) {

					System.err.println("Could not receive message.");
					e4.printStackTrace();
				}
				if(message != null) {
					message.rewind();
					messageID = message.get();

				}
				else {
					if((System.currentTimeMillis() - timeSinceLast) > 120000) {
						if(weAreInterested) {
							try {
								link.sendMessage(MessageHandler.KEEP_ALIVE);
								continue;
							} catch (IOException e) {
								if(e instanceof SocketException) {
									break;
								}
								System.err.println("Could not keep alive.");
								e.printStackTrace();
							}
						} else {
							break;
						}
					}
					continue;
				}
				//System.out.println("Message ID: "+messageID);
				switch(messageID) {
				case -2:
					System.out.println("Connection terminated.");
					break outerloop;
				case MessageHandler.KEEP_ALIVE:
					System.out.println("Keep alive acknowledged.");
					break;
				case MessageHandler.CHOKE:
					System.out.println("We are now choked.");
					peerIsChoking = true;
					break;
				case MessageHandler.UNCHOKE:
					System.out.println("We are now unchoked.");
					peerIsChoking = false;
					break;
				case MessageHandler.INTERESTED:
					System.out.println("Peer is interested.");
					if(weAreChoking) {
						try {
							link.sendMessage(MessageHandler.UNCHOKE);
						} catch (IOException e2) {
							if(e2 instanceof SocketException) {
								break;
							}

							System.err.println("Error sending unchoke message.");
							e2.printStackTrace();
						}
					}
					weAreChoking = false;
					peerIsInterested = true;
					break;
				case MessageHandler.UNINTERESTED:
					System.out.println("Peer is uninterested.");
					if(!weAreChoking) {
						try {
							link.sendMessage(MessageHandler.CHOKE);
						} catch (IOException e3) {
							if(e3 instanceof SocketException) {
								break;
							}
							System.err.println("Error sending choke message.");
						}
					}
					break;
				case MessageHandler.HAVE:
					System.out.println("Peer received piece "+message.getInt());
					break;
				case MessageHandler.BITFIELD:
					try{
						pieceAccess.take();
						setAvailablePieces(piecesHeldByPeer, message);
						pieceAccess.add(1);
					} catch (Exception e) {
						if(e instanceof SocketException) {
							break;
						}
						System.err.println("Could not update using bitfield.");
					}
					break;	
				case MessageHandler.REQUEST:
					System.out.println("Peer has requested a piece.");
					if(weAreChoking)
						break;
					if(!peerIsInterested)
						break;
					int uploadPieceInd = message.getInt();
					int uploadOffset = message.getInt();
					int uploadLength = message.getInt();
					ByteBuffer upload = ByteBuffer.allocate(uploadLength); //create the buffer based on the length of the requested piece
					int uploadEnd = uploadLength + uploadOffset;
					try {
						pieceAccess.take();
						if(piecesCompleted[uploadPieceInd]) { //if we have the requested piece
							upload.putInt(uploadPieceInd);
							upload.putInt(uploadOffset);
							while(uploadOffset <= uploadEnd) {
								System.out.println("Loading Piece:");
								System.out.println(uploadOffset);
								upload.put(pieceData[uploadPieceInd].get(uploadOffset));
								uploadOffset++;
							}
							try {
								link.sendMessage(MessageHandler.PIECE, upload.array());
								System.out.println("Piece message sent.");
								uploaded += torrentInfo.piece_length;
							} catch (IOException e) {
								if(e instanceof SocketException) {
									break;
								}
								System.err.println("Problem connecting to peer.");
								e.printStackTrace();
							}
						}
						pieceAccess.add(1);
					} catch (Exception e) {
						if(e instanceof SocketException) {
							break;
						}
						System.err.println("Unable to send piece.");
					}
					break;
				case MessageHandler.PIECE:
					int pieceInd = message.getInt();
					int offset = message.getInt();
					if(pieceInd == 0) {
						try {
							trackerAccess.take();
							createGetRequest("started");
							timeSinceAnnounce = System.currentTimeMillis();
							trackerAccess.put(1);
						} catch (Exception e) {
							if(e instanceof SocketException) {
								break;
							}
							System.out.println("Unable to notify tracker.");
						}
					}
					try {
						pieceAccess.take();
						while(message.hasRemaining()) {
							pieceData[pieceInd].put(offset, message.get()); //putting the piece into the array
							offset++;
						}
						pieceCompletionIndex[pieceInd] = offset;
						if(pieceData[pieceInd].capacity() == offset) {
							piecesHeldByPeer.remove(new Integer(pieceInd));
							piecesCompleted[pieceInd] = true;
							downloaded += pieceData[pieceInd].capacity();
						}
						try {
							if(toSHA(pieceData[pieceInd].array()).equals(byteToHexStr(pieceHashes[pieceInd].array()))) {
								//System.out.println("Verified "+pieceInd);

							} else {
								System.out.println("Hash not verified. Dumping piece.");
								piecesCompleted[pieceInd] = false;
								pieceCompletionIndex[pieceInd] = 0;
								pieceData[pieceInd].clear();
								break;
							}
						} catch (NoSuchAlgorithmException e) {
							e.printStackTrace();
						}
						ByteBuffer pieceID = ByteBuffer.allocate(4);
						pieceID.putInt(pieceInd);

						try {
							link.sendMessage(MessageHandler.HAVE, pieceID.array());
						} catch (IOException e1) {
							if(e1 instanceof SocketException) {
								break;
							}
							System.out.println("Problem connecting to peer.");
							e1.printStackTrace();
						}
						pieceAccess.put(1);
					} catch (Exception e) {
						if(e instanceof SocketException) {
							break;
						}
						System.err.println("Unable to receive piece.");
					}
					break;

				default:
					System.out.println("Invalid message.");
					break;
				}
				try {
					pieceAccess.take();
					boolean done = true;
					for(boolean b:piecesCompleted) {
						if(!b)
							done = false;
					}
					if(done) {
						weAreInterested = false;
						link.sendMessage(MessageHandler.UNINTERESTED);
					}

					pieceAccess.put(1);
				} catch (Exception e) {
					if(e instanceof SocketException) {
						break;
					}
					System.err.println("Could not count pieces completed.");
					e.printStackTrace();
				}
				timeSinceLast = System.currentTimeMillis();
			}

			link.close();
			System.out.println("Successfully closed link.");
		}

	}

	private static class PeerConnectionListener implements Runnable {
		MessageHandler link;
		LinkedBlockingQueue<ByteBuffer> messagesIncoming;
		private PeerConnectionListener(MessageHandler l, LinkedBlockingQueue<ByteBuffer> mI) {
			link = l;
			messagesIncoming = mI;
		}
		@Override
		public void run() {

			ByteBuffer message;
			try {
				while(true) {
					message = link.receiveMessage();
					
					if(message.get(0) == -2) {
						messagesIncoming.add(message);
						break;
					}
					messagesIncoming.add(message);
					

					
					
				}
			} catch (IOException e) {
			}
			System.out.println("Thread end.");

		}

	}

	private static class PeerConnectionEstablisher implements Runnable {

		@Override
		public void run() {
			try{
				Socket peerConnection = connectionsEstablished.take();
				while(peerConnection.getPort() != 8888) {
					new Thread(new PeerConnectionSender(new MessageHandler(peerConnection))).start();
					peerConnection = connectionsEstablished.take();
				}
				System.out.println("Connection establisher was closed.");
			}catch(Exception e) {
				System.err.println("Connection could not be established.");
				e.printStackTrace();
			}
		}

	}
	//Parses the .torrent file and creates torrentInfo object
	private static TorrentInfo parseFile(String fileName) {

		File torrentFile = new File(fileName);
		byte[] torrentfb = new byte[(int) torrentFile.length()];
		FileInputStream torrentfis = null;
		TorrentInfo parsedFile = null;

		try{ 
			torrentfis = new FileInputStream(torrentFile); //opening file for reading
		} catch (FileNotFoundException e) {
			System.out.println("Error opening the file.");
			e.printStackTrace();
		}

		try {
			torrentfis.read(torrentfb); //read to byte array
		} catch (IOException e1) {
			System.out.println("Error while reading from file stream.");
			e1.printStackTrace();
		} 

		try {
			parsedFile = new TorrentInfo(torrentfb); //create TorrentInfo object
		} catch (BencodingException e) {
			System.out.println("Error while parsing data.");
			e.printStackTrace();
		}

		try {
			torrentfis.close(); //close filestream
		} catch (IOException e) {
			System.out.println("Error while closing file stream.");
			e.printStackTrace();
		}

		//System.out.println(parsedFile.announce_url); //just testing to see what the ip/port# are
		return parsedFile;
	}


	public static String byteToHexStr(byte[] bytes) {
		char[] hexArray = "0123456789ABCDEF".toCharArray();
		StringBuilder sb = new StringBuilder(bytes.length * 3);
		for ( int j = 0; j < bytes.length; j++ ) {
			int v = bytes[j] & 0xFF;
			sb.append('%').append(hexArray[v >>> 4]).append(hexArray[v & 0x0F]);
		}
		return sb.toString();
	}

	private static byte[] createGetRequest() {
		return createGetRequest("");
	}


	private static byte[] createGetRequest(String event) {

		byte[] requestArray = null;

		StringBuilder s = new StringBuilder("?info_hash=");
		String ih = byteToHexStr(torrentInfo.info_hash.array()); 
		s.append(ih);

		/****Creating peer_id****/
		s.append("&peer_id=");
		s.append(ourID);

		s.append("&ip=");
		s.append(torrentInfo.announce_url.toString().substring(7, 20)); //add ip

		s.append("&port=");
		s.append(torrentInfo.announce_url.toString().substring(21, 25)); //add port

		s.append("&uploaded="+uploaded); //add uploaded bytes

		s.append("&downloaded="+downloaded); //add downloaded bytes

		s.append("&left=" + torrentInfo.file_length); //add what's left of file

		if(event.length() > 0)
			s.append("&event="+event);

		String requestURL = torrentInfo.announce_url.toString() + s.toString(); //build the URL
		System.out.println(requestURL); //to view request URL

		/*****Sending GET request*****/
		try {
			HttpURLConnection con = (HttpURLConnection) new URL(requestURL).openConnection();
			DataInputStream dis = new DataInputStream(con.getInputStream());
			requestArray = new byte[con.getContentLength()];
			dis.readFully(requestArray);
			dis.close();
		} catch (MalformedURLException e) {
			System.out.println("Error opening connection at provided URL.");
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
		return requestArray;
	}


	public static void testTrackerMessage(TrackerMessage tm) {
		for(Peer p:tm.peerList) {
			System.out.println(p.port);
			System.out.println(p.ip);
			System.out.println(p.peerid);
		}
	}

	public static ArrayList<Peer> getValidPeers(TrackerMessage tm) {
		ArrayList<Peer> listOfValidPeers = new ArrayList<Peer>();
		for(Peer p:tm.peerList) {
			if(p.ip.equals("128.6.171.130") || p.ip.equals("128.6.171.131") || p.peerid.contains("-AZ"))
				listOfValidPeers.add(p);
		}
		return listOfValidPeers;
	}




	public static byte[] createBitfield() { //generates bitfield 
		BitSet bitPayload = new BitSet(piecesCompleted.length);
		for(int i=0; i < piecesCompleted.length; i++) {
			if(piecesCompleted[i]) {
				bitPayload.set(i);
			} else {
				bitPayload.set(i, false);
			}
		}
		return bitsToBytes(bitPayload);
	}

	public static byte[] bitsToBytes(BitSet bits) {
		byte[] bytes = new byte[(bits.length() + 7) / 8];       
		for (int i = 0; i < bits.length(); i++) {
			if (bits.get(i)) {
				bytes[i / 8] |= 1 << (7 - i % 8);
			}
		}
		return bytes;
	}
/*** rarest piece stuff, commented out for now
	public static ArrayList<Integer> locatePeer(String peerID) {
		for(int i=0; i < peerList.size(); i++) {
			if(peerID == peerList.get(i).peerid) {
				return peerList.get(i).bitfield;
			}
		}
		System.out.println("Not in connection list?");
		System.out.println(peerID);
		return null;
	}

	public static ArrayList<numPieces> findRarestPiece() { //should find and sort rarest pieces
		ArrayList<numPieces> pieceCounts = new ArrayList<numPieces>(torrentInfo.piece_hashes.length);
		for(int n=0; n < torrentInfo.piece_hashes.length; n++) {
			pieceCounts.add(new numPieces(n)); //filling with piece indexes from 0 to pieces_hashes.length
		}
		for(int i=0; i < peerList.size(); i++) { //finding the pieces in the peerlist peers' bitfields and adding to ubiquity if matches are found
			for(int x=0; x < pieceCounts.size(); x++) {
				for(int j=0; j < peerList.get(i).bitfield.size(); j++) {
					if(pieceCounts.get(x).getPieceInd() == peerList.get(i).bitfield.get(j)) {
						pieceCounts.get(x).moreUbiquity();
					}
				}
			}
		}
		Collections.sort(pieceCounts);
		return pieceCounts;
		
	}
	
	private static class numPieces implements Comparable<numPieces> { //class which creates list of sorted pieces+frequency
		private int pieceInd;
		private Integer ubiquity;
		
		public int getPieceInd() {
			return pieceInd;
		}
		
		public int getUbiquity() { 
			return ubiquity;
		}
		
		public void moreUbiquity() {
			ubiquity++;
		}
		
		public numPieces(int pieceInd) {
			this.pieceInd = pieceInd;
			this.ubiquity = 0;
		}
		
		public int compareTo(numPieces other) {
			return ubiquity.compareTo(other.ubiquity);
		}
	}
***/

	public static void setAvailablePieces(ArrayList<Integer> piecesHeld, ByteBuffer message) {
		int index = 0;
		while(message.hasRemaining()) {
			int itsAByte = message.get();
			for(int bit = 7; bit >= 0; bit--) {
				if((itsAByte & (1<<bit)) != 0){

					piecesHeld.add(index);
				}
				index++;
			}
		}
	}

	public static String generateNewID() {
		Random rand = new Random();
		String s = "";
		int Low = 0;
		int High = 10;
		int i = 0;
		while(i<15) {
			int R = rand.nextInt(High-Low) + Low;
			s += ""+R;
			i++;
		}
		s="12345"+s; //so we can identify our peerID
		return s;
	}

	public static String toSHA(byte[] piece) throws NoSuchAlgorithmException{
		MessageDigest md = MessageDigest.getInstance("SHA-1"); 
		return byteToHexStr(md.digest(piece));
	}

	public static void main(String[] args) {

		if(args.length != 2){
			System.out.println("Sorry, wrong number of args inputted");
			return;
		}
		torrentInfo = parseFile(args[0]);
		pieceAccess = new LinkedBlockingQueue<Integer>();

		piecesCompleted = new boolean[torrentInfo.piece_hashes.length];

		programEnd = false;
		downloaded = 0;
		uploaded = 0;
		pieceData = new ByteBuffer[torrentInfo.piece_hashes.length];
		for(int i = 0; i < pieceData.length; i++) {
			pieceData[i] = ByteBuffer.allocate(torrentInfo.piece_length);
		}
		try {
			File f = new File("progress.ser");
			if(f.exists()) {
				RandomAccessFile file = new RandomAccessFile(args[1], "rw");
				FileInputStream fileIn = new FileInputStream("progress.ser");
				ObjectInputStream in = new ObjectInputStream(fileIn);
				BTData data = (BTData) in.readObject();
				downloaded = data.getDownloaded();
				System.out.println("downloaded: "+downloaded+", uploaded: "+uploaded);
				uploaded = data.getUploaded();
				piecesCompleted = data.piecesCompleted();
				for(int i = 0; i < piecesCompleted.length; i++) {
					if(piecesCompleted[i]) {
						file.seek(i * torrentInfo.piece_length);
						file.read(pieceData[i].array(), 0, torrentInfo.piece_length);		        	}
				}
				in.close();
				fileIn.close();
				file.close();
			}

		} catch (Exception e2) {
			System.err.println("File unopenable.");
			e2.printStackTrace();
			return;
		}
		pieceAccess.add(1);
		startedDownloading = false;

		ourID = generateNewID();
		System.out.println(ourID);

		byte[] response = createGetRequest();

		TrackerMessage tm = new TrackerMessage(response);



		pieceCompletionIndex = new int[torrentInfo.piece_hashes.length];
		ArrayList<Peer> connectPeers = getValidPeers(tm);
		minInterval = tm.minInterval;
		trackerAccess = new LinkedBlockingQueue<Integer>();
		trackerAccess.add(1);
		connectionsWaiting = new LinkedBlockingQueue<Socket>();
		connectionsEstablished = new LinkedBlockingQueue<Socket>();
		if(connectPeers == null) {
			System.out.println("No valid peers.");
		} else {
			for(Peer p: connectPeers) {
				try {
					System.out.println(p.peerid);
					System.out.println(p.port);
					connectionsWaiting.add(new Socket(p.ip, p.port));
				} catch (Exception e) {
					System.err.println("Could not create Socket with given ip/port");
					e.printStackTrace();
				}
			}
		}

		PeerConnectionEstablisher joiner = new PeerConnectionEstablisher();
		Thread a = new Thread(joiner);
		a.start();
		HandshakeEstablisher diplomat = new HandshakeEstablisher();
		Thread b = new Thread(diplomat);
		b.start();
		SocketSpawner socketListening = new SocketSpawner();
		Thread c = new Thread(socketListening);
		c.start();



		Scanner sc = new Scanner(System.in);
		sc.nextLine();
		programEnd = true;
		System.out.println("Main program terminated.");

		try {
			server.close();
			connectionsWaiting.add(new Socket("localhost", 8888));
			connectionsEstablished.add(new Socket("localhost", 8888));

		} catch (Exception e1) {

		}

		sc.close();

		try {
			RandomAccessFile file = new RandomAccessFile(args[1], "rw");
			FileOutputStream fileOut = new FileOutputStream("progress.ser");
			ObjectOutputStream out = new ObjectOutputStream(fileOut);
			out.writeObject(new BTData(downloaded, uploaded, piecesCompleted));
			for(int i = 0; i < piecesCompleted.length; i++) {
				if(piecesCompleted[i]) {
					file.seek(i * torrentInfo.piece_length);
					file.write(pieceData[i].array());		        	
				}
			}
			out.close();
			fileOut.close();
			file.close();

		} catch (Exception e2) {
			System.err.println("File unopenable.");
			e2.printStackTrace();
			return;
		}

		for(boolean q:piecesCompleted) {
			if(!q) {
				createGetRequest("stopped");
				return;
			}
		}
		createGetRequest("completed");

		System.out.println("File completed. Mission accomplished!");

	}

}
