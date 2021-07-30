package com.ianrenton.planesailing.comms;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ianrenton.planesailing.app.TrackTable;
import com.ianrenton.planesailing.data.Aircraft;

/**
 * Receiver for aircraft data in SBS ("BaseStation") format: comma-separated
 * values and line breaks. (This is output by Dump1090 on port 30003 for
 * directly received messages, and MLAT information can be output in the same
 * format by enabling a config option.)
 */
public class SBSTCPClient extends TCPClient {

	private static final String DATA_TYPE_ADSB = "SBS format (ADS-B) data";
	private static final String DATA_TYPE_MLAT = "SBS format (MLAT) data";
	private static final Logger LOGGER = LogManager.getLogger(SBSTCPClient.class);

	private final String dataType;
	private final int socketTimeoutMillis;
	
	/**
	 * Create the client
	 * 
	 * @param remoteHost Host to connect to.
	 * @param remotePort Port to connect to.
	 * @param trackTable The track table to use.
	 * @param mlat true if this connection will be receiving MLAT data, false if it
	 * will be receiving Mode-S/ADS-B data from a local radio.
	 */
	public SBSTCPClient(String remoteHost, int remotePort, TrackTable trackTable, boolean mlat) {
		super(remoteHost, remotePort, trackTable);
		dataType = mlat ? DATA_TYPE_MLAT : DATA_TYPE_ADSB;
		socketTimeoutMillis = mlat ? 600000 : 60000; // 1 min for local data, 10 min for MLAT from server
	}

	@Override
	protected boolean read(InputStream in) {
		try {
			String line = new BufferedReader(new InputStreamReader(in)).readLine();
			if (line != null) {
				updatePacketReceivedTime();
				handle(line);
			}
			return true;
		} catch (IOException ex) {
			getLogger().warn("Exception encountered in TCP Socket for {}.", getDataType(), ex);
			return false;
		}
	}

	/**
	 * Handle a new line of SBS format data.
	 * 
	 * @param m
	 */
	private void handle(String m) {
		try {
			String[] fields = m.split(",");
			String icaoHex = fields[4];

			// If this is a new track, add it to the track table
			if (!trackTable.containsKey(icaoHex)) {
				trackTable.put(icaoHex, new Aircraft(icaoHex));
			}

			// Extract the data and update the track
			Aircraft a = (Aircraft) trackTable.get(icaoHex);

			if (fields[0].equals("MSG")) {
				if (fields.length > 10) {
					String callsign = fields[10].trim();
					if (!callsign.isEmpty()) {
						a.setCallsign(callsign);
					}
				}

				if (fields.length > 11) {
					String altitude = fields[11].trim();
					if (!altitude.isEmpty()) {
						a.setAltitude(Double.valueOf(altitude));
					}
				}

				if (fields.length > 12) {
					String speed = fields[12].trim();
					if (!speed.isEmpty()) {
						a.setSpeed(Double.valueOf(speed));
					}
				}

				if (fields.length > 13) {
					String course = fields[13].trim();
					if (!course.isEmpty()) {
						a.setCourse(Double.valueOf(course));
						a.setHeading(Double.valueOf(course)); // SBS will never give us a separate mag heading so just
																// use
																// course as heading
					}
				}

				if (fields.length > 15) {
					String latitude = fields[14].trim();
					String longitude = fields[15].trim();
					if (!latitude.isEmpty() && !longitude.isEmpty()) {
						a.addPosition(Double.valueOf(latitude), Double.valueOf(longitude));
					}
				}

				if (fields.length > 16) {
					String verticalRate = fields[16].trim();
					if (!verticalRate.isEmpty()) {
						a.setVerticalRate(Double.valueOf(verticalRate));
					}
				}

				if (fields.length > 17) {
					String squawk = fields[17].trim();
					if (!squawk.isEmpty()) {
						a.setSquawk(Integer.valueOf(squawk));
					}
				}

				if (fields.length > 21) {
					String isOnGround = fields[21].trim();
					if (!isOnGround.isEmpty()) {
						a.setOnGround(isOnGround != "0");
					}
				}
				
				a.updateMetadataTime();
			}
		} catch (Exception ex) {
			getLogger().warn("TCP Socket for {} encountered an exception handling line {}", getDataType(), m, ex);
		}
	}

	@Override
	protected int getTimeoutMillis() {
		return socketTimeoutMillis;
	}

	@Override
	protected String getDataType() {
		return dataType;
	}

	@Override
	protected Logger getLogger() {
		return LOGGER;
	}
}
