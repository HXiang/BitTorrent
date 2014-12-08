package RUBTClient;

import java.util.ArrayList;

import GivenTools.TorrentInfo;

public class Peer {
	
	public int port;
	public String ip;
	public String peerid;
	public ArrayList<Integer> bitfield;
	public TorrentInfo ti;
	

	public Peer(int port, String ip, String peerid) {
		this.port = port;
		this.ip = ip;
		this.peerid = peerid;
	}
	
	
	
}
