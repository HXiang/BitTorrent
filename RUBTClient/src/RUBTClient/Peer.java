package RUBTClient;

import java.util.ArrayList;

import GivenTools.TorrentInfo;

public class Peer {
	
	public int port;
	public String ip;
	public String peerid;
	public TorrentInfo ti;
	public ArrayList<Integer> bitfield;
	

	public Peer(int port, String ip, String peerid) {
		this.port = port;
		this.ip = ip;
		this.peerid = peerid;
		this.bitfield = new ArrayList<Integer>();
	}
	
	
	
}
