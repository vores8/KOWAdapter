package com.khome.onewire.adapter;

import java.io.IOException;
import java.util.Enumeration;

import com.dalsemi.onewire.OneWireException;
import com.dalsemi.onewire.adapter.DSPortAdapter;
import com.dalsemi.onewire.adapter.OneWireIOException;
import com.dalsemi.onewire.adapter.SerialService;

public class KOWRFAdapter extends DSPortAdapter {

	private static final int MAX_CHANNELS = 1;
	private boolean channelReady[] = new boolean[MAX_CHANNELS];
	private static String classVersion = "0.10";
	private SerialService serial;
	private boolean haveLocalUse;
	private Object syncObject;
	private boolean adapterPresent;

	private String portName = null;
	private byte currentChannel = 0;
	private Object owState;

	protected static final String UNSPECIFIED_ERROR = "An unspecified error occurred.";
	protected static final String COMM_FAILED = "IO Error: ";

	private static final boolean doDebugMessages = false;

	private static final int POWER_DELIVERY_CONDITION_NONE = -1;

	private int POWER_DELIVERY_CONDITION = POWER_DELIVERY_CONDITION_NONE;

	private static final byte RET_SUCCESS = 0x00;
	private static final byte RET_FAILURE = (byte) 0xFF;
	private static final byte CMD_PINGCHANNEL = 0x10;
	private static final byte CMD_FINDFIRSTDEVICE = 0x11;
	private static final byte CMD_FINDNEXTDEVICE = 0x12;
	private static final byte CMD_GETADDRESS = 0x13;
	private static final byte CMD_SETSEARCHONLYALARMINGDEVICES = 0x14;
	private static final byte CMD_SETNORESETSEARCH = 0x15;
	private static final byte CMD_SETSEARCHALLDEVICES = 0x16;
	private static final byte CMD_PUTBIT = 0x17;
//	private static final byte CMD_PUTBYTE = 0x18;
	private static final byte CMD_GETBYTE = 0x19;
	private static final byte CMD_GETBLOCK = 0x1A;
	private static final byte CMD_DATABLOCK = 0x1B;
	private static final byte CMD_RESET = 0x1C;
	private static final byte CMD_PUTBYTE = 0x1D;
	private static final byte CMD_SETPOWERNORMAL = 0x1E;
	private static final byte CMD_TARGETFAMILY = 0x1F;
	private static final byte CMD_TARGETALLFAMILIES = 0x20;
	private static final byte CMD_SEARCHROM = 0x21;
	private static final byte CMD_MATCHROM = 0x22;

	private byte[] lastAddress = new byte[8];

	public KOWRFAdapter() {
		syncObject = new Object();
	}

	public String getAdapterName() {
		return "KOWRF";
	}

	@Override
	public String getPortTypeDescription() {
		return "serial communication port";
	}

	@Override
	public String getClassVersion() {
		return classVersion;
	}

	@Override
	public Enumeration getPortNames() {
		return SerialService.getSerialPortIdentifiers();
	}

	@Override
	public boolean selectPort(String newPortName) throws OneWireIOException,
			OneWireException {

		// find the port reference
		serial = SerialService.getSerialService(newPortName);
		// ( SerialService ) serailServiceHash.get(newPortName);

		// check if there is no such port
		if (serial == null)
			throw new OneWireException(
					"USerialAdapter: selectPort(), Not such serial port: "
							+ newPortName);

		try {

			// acquire exclusive use of the port
			beginLocalExclusive();

			// attempt to open the port
			serial.openPort();
			serial.setBaudRate(9600);
			serial.setNotifyOnDataAvailable(true);
			// erial.setDTR(true);
			// serial.setRTS(true);
			// serial.sendBreak(50);

			portName = newPortName;

			return true;
		} catch (IOException ioe) {
			throw new OneWireIOException(ioe.toString());
		} finally {

			// release local exclusive use of port
			endLocalExclusive();
		}
	}

	@Override
	public void freePort() throws OneWireException {
		try {
			// acquire exclusive use of the port
			beginLocalExclusive();

			adapterPresent = false;

			// attempt to close the port
			serial.closePort();
		} catch (IOException ioe) {
			throw new OneWireException("Error closing serial port");
		} finally {

			// release local exclusive use of port
			endLocalExclusive();
		}
	}

	@Override
	public String getPortName() throws OneWireException {
		if (serial != null)
			return serial.getPortName();
		else
			throw new OneWireException(
					"KOWAdapter-getPortName, port not selected");
	}

	@Override
	public boolean adapterDetected() throws OneWireIOException,
			OneWireException {
		boolean rt = false;
		for (byte i = 0; i < MAX_CHANNELS; i++) {
			channelReady[i] = false;
			sendCommand(i, CMD_PINGCHANNEL);
			try {
				Thread.sleep(1000);
				getMessage(i, 0);
				channelReady[i] = true;
				rt = true;
			} catch (Exception e) {
				if (doDebugMessages)
					System.err.println("KOWAcdapter.adapterDetected: " + e);
			}
		}

		return rt;
	}

	private byte getMessage(byte channel) throws IOException, OneWireException {
		byte result[] = getMessage(channel, 1);
		return result[0];
	}

	private byte[] getMessage(byte channel, int datalen) throws IOException,
			OneWireException {
		byte result[] = null;
		byte message[] = new byte[datalen + 3];
		int received = serial.readWithTimeout(message, 0, message.length);
		if (received != message.length) {
			throw new IOException("getMessage failed: waiting "
					+ message.length + " got " + received);
		}
		if (doDebugMessages) {
			System.out.print('<');
			for (int i = 0; i < message.length; i++) {
				String out = String.format("#%02X", message[i]);
				System.out.print(out);
			}
			System.out.println();
		}

		byte ch = message[0];
		byte retVal = message[1];
		if (retVal != RET_SUCCESS || ch != channel) {
			String errorMsg;
			if (retVal == RET_FAILURE) {
				// should be a standard error message after RET_FAILURE
				errorMsg = "failure from remote channel";
				throw new OneWireException(errorMsg);
			} else {
				// didn't even get RET_FAILURE
				errorMsg = UNSPECIFIED_ERROR;

				// that probably means we have a major communication error.
				// better to disconnect and reconnect.
				// freePort();
				// selectPort(portName);
			}
		}
		if (datalen > 0) {
			result = new byte[datalen];
			for (int i = 0; i < datalen; i++) {
				result[i] = (byte) message[i + 3];
			}
		}
		return result;
	}

	private void sendCommand(byte channel, byte command) {
		sendCommand(channel, command, null);
	}

	private void sendCommand(byte channel, byte command, byte data[]) {
		try {
			byte[] msg;
			if (data == null) {
				msg = new byte[4];
				msg[3] = 0;
			} else {
				msg = new byte[data.length + 4];
				msg[3] = (byte) data.length;
				for (int i = 0; i < data.length; i++)
					msg[i + 4] = data[i];
			}
			msg[0] = (byte) 0xDD;
			msg[1] = channel;
			msg[2] = command;
			serial.write(msg, 0, msg.length);
			serial.flush();
			if (doDebugMessages) {
				System.out.print('>');
				for (int i = 0; i < msg.length; i++) {
					String out = String.format("#%02X", msg[i]);
					System.out.print(out);
				}
				System.out.println();
			}

		} catch (IOException e) {
			if (doDebugMessages)
				System.err.println("KOWAcdapter.sendCommand: " + e);
		}
	}

	private void sendCommand(byte channel, byte command, byte data) {
		byte b[] = new byte[1];
		b[0] = data;
		sendCommand(channel, command, b);
	}

	public boolean findFirstDevice(byte c) throws OneWireIOException,
			OneWireException {
		for (byte i = c; i < MAX_CHANNELS; i++) {
			currentChannel = i;
			if (channelReady[i]) {
				sendCommand(i, CMD_FINDFIRSTDEVICE);
				try {
					byte found = getMessage(i);
					if (found == 1) {
						sendCommand(i, CMD_GETADDRESS);
						lastAddress = getMessage(i, lastAddress.length);
						return true;
					}
				} catch (IOException e) {
					if (doDebugMessages)
						System.err.println("KOWAcdapter.findFirstDevice: " + e);
				}
			}
		}
		return false;
	}

	@Override
	public boolean findFirstDevice() throws OneWireIOException,
			OneWireException {
		// for (currentChannel = 0; currentChannel < MAX_CHANNELS;
		// currentChannel++) {
		if (!adapterDetected())
			return false;
		if (findFirstDevice((byte) 0))
			return true;
		// }
		currentChannel = 0;
		return false;
	}

	@Override
	public boolean findNextDevice() throws OneWireIOException, OneWireException {
		for (byte i = currentChannel; i < MAX_CHANNELS; i++) {
			currentChannel = i;
			if (channelReady[i]) {
				sendCommand(i, CMD_FINDNEXTDEVICE);
				try {
					byte found = getMessage(i);
					if (found == 1) {
						sendCommand(i, CMD_GETADDRESS);
						lastAddress = getMessage(i, lastAddress.length);
						return true;
					}
				} catch (IOException e) {
					if (doDebugMessages)
						System.err.println("KOWAcdapter.findFirstDevice: " + e);
				}
				return findFirstDevice((byte) (currentChannel + 1));
			}
		}
		return false;
	}

	@Override
	public void getAddress(byte[] address) {
		System.arraycopy(lastAddress, 0, address, 0, address.length);
	}

	@Override
	public void setSearchOnlyAlarmingDevices() {
		for (byte i = 0; i < MAX_CHANNELS; i++) {
			if (channelReady[i]) {

				sendCommand(i, CMD_SETSEARCHONLYALARMINGDEVICES);
				try {
					getMessage(i);
				} catch (IOException | OneWireException e) {
					if (doDebugMessages)
						System.err
								.println("KOWAcdapter.setSearchOnlyAlarmingDevices: "
										+ e);
				}
			}
		}
	}

	@Override
	public void setNoResetSearch() {
		for (byte i = 0; i < MAX_CHANNELS; i++) {
			if (channelReady[i]) {

				sendCommand(i, CMD_SETNORESETSEARCH);
				try {
					getMessage(i);
				} catch (IOException | OneWireException e) {
					if (doDebugMessages)
						System.err
								.println("KOWAcdapter.setNoResetSearch: " + e);
				}
			}
		}
	}

	@Override
	public void setSearchAllDevices() {
		// for (byte i = 0; i < MAX_CHANNELS; i++) {
		// if (channelReady[i]) {
		// sendCommand(i, CMD_SETSEARCHALLDEVICES);
		// try {
		// checkReturnValue(i);
		// } catch (IOException | OneWireException e) {
		// if (doDebugMessages)
		// System.err.println("KOWAcdapter.setSearchAllDevices: "
		// + e);
		// }
		// }
		// }
	}

	@Override
	public boolean beginExclusive(boolean blocking) throws OneWireException {
		return serial.beginExclusive(blocking);
	}

	@Override
	public void endExclusive() {
		serial.endExclusive();
	}

	@Override
	public void putBit(boolean bitValue) throws OneWireIOException,
			OneWireException {
		sendCommand(currentChannel, CMD_PUTBIT, bitValue ? (byte) 1 : (byte) 0);
		try {
			getMessage(currentChannel);
		} catch (IOException | OneWireException e) {
			if (doDebugMessages)
				System.err.println("KOWAcdapter.putBit: " + e);
		}
	}

	@Override
	public boolean getBit() throws OneWireIOException, OneWireException {
		sendCommand(currentChannel, CMD_PUTBIT);
		try {
			return getMessage(currentChannel) == 0 ? false : true;
		} catch (IOException e) {
			throw new OneWireException(COMM_FAILED + e.getMessage());
		}
	}

	@Override
	public void putByte(int byteValue) throws OneWireIOException,
			OneWireException {
//		byte[] temp_block = new byte[1];
//		temp_block[0] = (byte) byteValue;
//		dataBlock(temp_block, 0, 1);
		
//		switch (POWER_DELIVERY_CONDITION) {
//		case POWER_DELIVERY_CONDITION_NONE: {
//			byte[] temp_block = new byte[1];
//			temp_block[0] = (byte) byteValue;
//			dataBlock(temp_block, 0, 1);
//		}
//			break;
//		case DSPortAdapter.CONDITION_AFTER_BYTE: {
			sendCommand(currentChannel, CMD_PUTBYTE, (byte) byteValue);
//			POWER_DELIVERY_CONDITION = POWER_DELIVERY_CONDITION_NONE;
//
//		}
//			break;
//		}
	}

	@Override
	public int getByte() throws OneWireIOException, OneWireException {
		sendCommand(currentChannel, CMD_GETBYTE);
		try {
			int b = getMessage(currentChannel);
			return b;
		} catch (IOException e) {
			throw new OneWireException(COMM_FAILED + e.getMessage());
		}
	}

	@Override
	public byte[] getBlock(int len) throws OneWireIOException, OneWireException {
		byte[] buffer = new byte[len];
		getBlock(buffer, 0, len);
		return buffer;
	}

	@Override
	public void getBlock(byte[] arr, int len) throws OneWireIOException,
			OneWireException {
		getBlock(arr, 0, len);
	}

	@Override
	public void getBlock(byte[] arr, int off, int len)
			throws OneWireIOException, OneWireException {
		sendCommand(currentChannel, CMD_GETBLOCK, (byte) len);
		try {
			byte[] data = getMessage(currentChannel, len);
			System.arraycopy(data, 0, arr, off, len);
		} catch (IOException e) {
			throw new OneWireException(COMM_FAILED + e.getMessage());
		}
	}

	@Override
	public void dataBlock(byte[] dataBlock, int off, int len)
			throws OneWireIOException, OneWireException {
		if (doDebugMessages) {
			System.out.println("DataBlock called for " + len + " bytes");
		}

		byte[] data = new byte[len];
//		data[0] = (byte) len;
		for (int i = 0; i < len; i++) {
			data[i] = dataBlock[off + i];
		}

		sendCommand(currentChannel, CMD_DATABLOCK, data);

		try {
			Thread.sleep(1);
			data = getMessage(currentChannel, len);
			for (int i = 0; i < len; i++) {
				dataBlock[off + i] = data[i];
			}

			// } catch (InterruptedException e) {
		} catch (IOException e) {
			throw new OneWireException(COMM_FAILED + e.getMessage());
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		if (doDebugMessages) {
			System.out.println("   Done DataBlocking");
		}

	}

	@Override
	public int reset() throws OneWireIOException, OneWireException {
		sendCommand(currentChannel, CMD_RESET);
		try {
			// Thread.sleep(50);
			getMessage(currentChannel, 0);
		} catch (IOException e) {
			throw new OneWireException(COMM_FAILED + e.getMessage());
		}
		return 1;
	}

	private void beginLocalExclusive() throws OneWireException {

		// check if there is no such port
		if (serial == null)
			throw new OneWireException("USerialAdapter: port not selected ");

		// check if already have exclusive use
		if (serial.haveExclusive())
			return;
		else {
			while (!haveLocalUse) {
				synchronized (syncObject) {
					haveLocalUse = serial.beginExclusive(false);
				}
				if (!haveLocalUse) {
					try {
						Thread.sleep(50);
					} catch (Exception e) {
					}
				}
			}
		}
	}

	private void endLocalExclusive() {
		synchronized (syncObject) {
			if (haveLocalUse) {
				haveLocalUse = false;

				serial.endExclusive();
			}
		}
	}

	public void setPowerDuration(int timeFactor) throws OneWireIOException,
			OneWireException {
	}

	public boolean startPowerDelivery(int changeCondition)
			throws OneWireIOException, OneWireException {
			return true;
	}

	public void targetFamily(int family) {
		// send targetFamily command
		for (byte i = 0; i < MAX_CHANNELS; i++) {
			if (channelReady[i]) {
				sendCommand(i, CMD_TARGETFAMILY, (byte) family);
				try {
					getMessage(i);
				} catch (IOException | OneWireException e) {
					if (doDebugMessages)
						System.err.println("KOWAcdapter.targetFamily: " + e);
				}
			}
		}
	}

	public void targetAllFamilies() {
		// send targetFamily command
		for (byte i = 0; i < MAX_CHANNELS; i++) {
			if (channelReady[i]) {
				sendCommand(i, CMD_TARGETALLFAMILIES);
				try {
					getMessage(i, 0);
				} catch (IOException | OneWireException e) {
					if (doDebugMessages)
						System.err.println("KOWAcdapter.targetAllFamilies: "
								+ e);
				}
			}
		}
	}

	@Override
	public boolean isPresent(byte[] address) throws OneWireIOException,
			OneWireException {
		for (currentChannel = 0; currentChannel < MAX_CHANNELS; currentChannel++) {
			try {
				byte r;
//				sendCommand(currentChannel, CMD_RESET);
//				r = getMessage(currentChannel);
//				if (r == 1) {
					sendCommand(currentChannel, CMD_SEARCHROM, address);
					r = getMessage(currentChannel);
					if (r == 1)
						return true;
//				}
			} catch (IOException e) {
				if (doDebugMessages)
					System.err.println("KOWAcdapter.isPresent: " + e);
			}
		}
		currentChannel = 0;
		return false;
	}

	@Override
	public boolean select(byte[] address) throws OneWireIOException,
			OneWireException {
		sendCommand(currentChannel, CMD_MATCHROM, address);
		try {
			byte r = getMessage(currentChannel);
			if (r == 1)
				return true;
		} catch (IOException e) {
			if (doDebugMessages)
				System.err.println("KOWAcdapter.isPresent: " + e);
		}
		return false;

	}

}
