package com.ianrenton.planesailing.comms;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.charset.StandardCharsets;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.ianrenton.planesailing.app.TrackTable;
import com.ianrenton.planesailing.data.Ship;
import com.ianrenton.planesailing.data.TrackType;

import dk.tbsalling.aismessages.AISInputStreamReader;
import dk.tbsalling.aismessages.ais.messages.AISMessage;
import dk.tbsalling.aismessages.ais.messages.AidToNavigationReport;
import dk.tbsalling.aismessages.ais.messages.BaseStationReport;
import dk.tbsalling.aismessages.ais.messages.ClassBCSStaticDataReport;
import dk.tbsalling.aismessages.ais.messages.ExtendedClassBEquipmentPositionReport;
import dk.tbsalling.aismessages.ais.messages.LongRangeBroadcastMessage;
import dk.tbsalling.aismessages.ais.messages.PositionReportClassAAssignedSchedule;
import dk.tbsalling.aismessages.ais.messages.PositionReportClassAScheduled;
import dk.tbsalling.aismessages.ais.messages.ShipAndVoyageData;
import dk.tbsalling.aismessages.ais.messages.StandardClassBCSPositionReport;

/**
 * Receiver for AIS NMEA-0183 messages from a UDP socket.
 */
public class AISUDPReceiver {
	private static final Logger LOGGER = LogManager.getLogger(AISUDPReceiver.class);
	private final int localPort;
	private final TrackTable trackTable;
	private final UDPReceiver udpReceiverThread = new UDPReceiver();
	private final AISReceiver aisReceiverThread = new AISReceiver();
	private AISInputStreamReader aisReader;
	private final PipedOutputStream pipeOS = new PipedOutputStream();
	private InputStream pipeIS;
	private boolean run = true;

	/**
	 * Create the receiver
	 * @param localPort Port to listen on.
	 * @param trackTable The track table to use.
	 */
	public AISUDPReceiver(int localPort, TrackTable trackTable) {
		this.localPort = localPort;
		this.trackTable = trackTable;
		
		try {
			 pipeIS = new PipedInputStream(pipeOS);
			 aisReader = new AISInputStreamReader(pipeIS, aisMessage -> handle(aisMessage));
		} catch (IOException ex) {
			LOGGER.error("Could not set up internal pipe for AIS Receiver", ex);
		}
	}

	/**
	 * Run the receiver.
	 */
	public void run() {
		run = true;
		new Thread(udpReceiverThread).start();
		new Thread(aisReceiverThread).start();
	}

	/**
	 * Stop the receiver.
	 */
	public void stop() {
		run = false;
		aisReader.requestStop();
	}

	/**
	 * Handle an incoming message.
	 * @param m
	 */
	private void handle(AISMessage m) {
		int mmsi = m.getSourceMmsi().getMMSI();
		String mmsiString = String.valueOf(mmsi);
		
		// If this is a new track, add it to the track table
		if (!trackTable.containsKey(mmsiString)) {
			trackTable.put(mmsiString, new Ship(mmsi));
			trackTable.get(mmsiString).setTrackType(TrackType.SHIP); // Assume ship by default
		}
		
		// Extract the data and update the track
		Ship s = (Ship) trackTable.get(mmsiString);
		switch (m.getMessageType()) {
		case AidToNavigationReport:
			AidToNavigationReport m2 = (AidToNavigationReport) m;
			s.setName(m2.getName().trim());
			s.addPosition(m2.getLatitude(), m2.getLongitude());
			s.setTrackType(TrackType.AIS_ATON);
			s.setFixed(true);
			break;
		case BaseStationReport:
			BaseStationReport m3 = (BaseStationReport) m;
			s.setShoreStation(true);
			s.addPosition(m3.getLatitude(), m3.getLongitude());
			s.setTrackType(TrackType.AIS_SHORE_STATION);
			s.setFixed(true);
			break;
		case ClassBCSStaticDataReport:
			ClassBCSStaticDataReport m4 = (ClassBCSStaticDataReport) m;
			if (m4.getShipName() != null) {
				s.setName(m4.getShipName().trim());
			}
			s.setCallsign(m4.getCallsign().trim());
			s.setShipType(m4.getShipType());
			s.setTrackType(TrackType.SHIP);
			break;
		case ExtendedClassBEquipmentPositionReport:
			ExtendedClassBEquipmentPositionReport m5 = (ExtendedClassBEquipmentPositionReport) m;
			s.setName(m5.getShipName().trim());
			s.addPosition(m5.getLatitude(), m5.getLongitude());
			if (m5.getCourseOverGround() != 511) {
				s.setCourse(m5.getCourseOverGround());
			}
			if (m5.getTrueHeading() != 511) {
				s.setHeading(m5.getTrueHeading());
			}
			s.setSpeed(m5.getSpeedOverGround());
			s.setTrackType(TrackType.SHIP);
			break;
		case LongRangeBroadcastMessage:
			LongRangeBroadcastMessage m6 = (LongRangeBroadcastMessage) m;
			s.addPosition(m6.getLatitude(), m6.getLongitude());
			if (m6.getCourseOverGround() != 511) {
				s.setCourse(m6.getCourseOverGround());
			}
			s.setSpeed(m6.getSpeedOverGround());
			s.setNavStatus(m6.getNavigationalStatus());
			s.setTrackType(TrackType.SHIP);
			break;
		case PositionReportClassAAssignedSchedule:
			PositionReportClassAAssignedSchedule m7 = (PositionReportClassAAssignedSchedule) m;
			s.addPosition(m7.getLatitude(), m7.getLongitude());
			if (m7.getCourseOverGround() != 511) {
				s.setCourse(m7.getCourseOverGround());
			}
			if (m7.getTrueHeading() != 511) {
				s.setHeading(m7.getTrueHeading());
			}
			s.setSpeed(m7.getSpeedOverGround());
			s.setNavStatus(m7.getNavigationStatus());
			s.setTrackType(TrackType.SHIP);
			break;
		case PositionReportClassAScheduled:
			PositionReportClassAScheduled m9 = (PositionReportClassAScheduled) m;
			s.addPosition(m9.getLatitude(), m9.getLongitude());
			if (m9.getCourseOverGround() != 511) {
				s.setCourse(m9.getCourseOverGround());
			}
			if (m9.getTrueHeading() != 511) {
				s.setHeading(m9.getTrueHeading());
			}
			s.setSpeed(m9.getSpeedOverGround());
			s.setNavStatus(m9.getNavigationStatus());
			s.setTrackType(TrackType.SHIP);
			break;
		case ShipAndVoyageRelatedData:
			ShipAndVoyageData m10 = (ShipAndVoyageData) m;
			s.setName(m10.getShipName().trim());
			s.setCallsign(m10.getCallsign().trim());
			s.setShipType(m10.getShipType());
			s.setDestination(m10.getDestination());
			s.setTrackType(TrackType.SHIP);
			break;
		case StandardClassBCSPositionReport:
			StandardClassBCSPositionReport m11 = (StandardClassBCSPositionReport) m;
			s.addPosition(m11.getLatitude(), m11.getLongitude());
			if (m11.getCourseOverGround() != 511) {
				s.setCourse(m11.getCourseOverGround());
			}
			if (m11.getTrueHeading() != 511) {
				s.setHeading(m11.getTrueHeading());
			}
			s.setSpeed(m11.getSpeedOverGround());
			s.setTrackType(TrackType.SHIP);
			break;
		default:
			// Nothing useful we can do with this type
			break;
		}
	}

	/**
	 * Inner receiver thread. Reads datagrams from the UDP socket, pipes them
	 * over to the third-party AISInputStreamReader.
	 */
	private class UDPReceiver implements Runnable {

		public void run() {
			try {
				DatagramSocket socket = new DatagramSocket(localPort);
				LOGGER.info("Opened local UDP port {} to receive AIS data.", localPort);

				while (run) {
					// Read packet from the UDP port and write to the internal pipe
					byte[] buffer = new byte[256];
					DatagramPacket p = new DatagramPacket(buffer, buffer.length);
					socket.receive(p);
					String line = new String(p.getData(), StandardCharsets.US_ASCII).trim() + "\r\n";
					pipeOS.write(line.getBytes());

					Thread.sleep(10);
				}

				socket.close();

			} catch (Exception ex) {
				LOGGER.error("Exception in AIS Receiver", ex);
			}
		}
	}

	/**
	 * Thread to kick off the AIS Receiver, which otherwise would block.
	 */
	private class AISReceiver implements Runnable {

		public void run() {
			aisReader.run();
		}
	}
}
