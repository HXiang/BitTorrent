package RUBTClient;

import java.io.Serializable;

public class BTData implements Serializable{

	private static final long serialVersionUID = 8126478679735981275L;
	private int downloaded;
	private int uploaded;
	private boolean[] piecesCompleted;
	public BTData(int d, int u, boolean[] pC) {
		downloaded = d;
		uploaded = u;
		piecesCompleted = new boolean[pC.length];
		for(int i = 0; i < pC.length; i++) {
			piecesCompleted[i] = pC[i];
		}
	}
	public int getDownloaded() {
		return downloaded;
	}
	public int getUploaded() {
		return uploaded;
	}
	public boolean[] piecesCompleted() {
		return piecesCompleted;
	}
}
