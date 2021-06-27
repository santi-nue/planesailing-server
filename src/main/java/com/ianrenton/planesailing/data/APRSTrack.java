package com.ianrenton.planesailing.data;

public class APRSTrack extends Track {
	private static final long serialVersionUID = 1L;
	private static final String DEFAULT_APRS_SYMBOL = "SFGPU-------";
	
	public APRSTrack(String id) {
		super(id);
		setTrackType(TrackType.APRS_TRACK);
		setSymbolCode(DEFAULT_APRS_SYMBOL);
		positionHistory.setHistoryLength(60 * 60 * 1000); // 1 hour
	}

	@Override
	public String getDisplayDescription1() {
		return "";
	}

	@Override
	public String getDisplayDescription2() {
		return "";
	}
}
