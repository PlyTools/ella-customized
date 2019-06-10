package com.apposcopy.ella.server;

import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Paths;
import java.text.DateFormat;
import java.text.SimpleDateFormat;

import com.google.gson.Gson;

import java.util.*;
import java.util.logging.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.io.*;

/*
 * @author Saswat Anand
 */
public class Server
{
	private static final Logger logger = Logger.getLogger(Server.class.getName());
	private static final int BUFSIZE = 102400;

	private static Map<String, Map<Integer, int[]>> mCoverageRecordMap = new HashMap<>();

	private List<Worker> workers = new CopyOnWriteArrayList<>();
	private Map<String,String> appIdToTraceId = new HashMap<>();
	private String ellaOutDir;
	private long maxIdleTime = 5000;

	public Server(String ellaOutDir)
	{
		this.ellaOutDir = ellaOutDir;
	}

	public static void main(String[] args) throws IOException
	{
		String ellaOutDir = args[0];
		int port = Integer.parseInt(args[1]);

		Server server = new Server(ellaOutDir);
		server.serve(port);

		System.exit(0);
	}

	/**
	 * 获取指定应用整体的覆盖率
	 * @param appId	应用Hash值
	 * @return
	 */
	public static double getAppMethodCoverage(String appId){
		int[] appMCovArray = null;
		if (mCoverageRecordMap.containsKey(appId)&&mCoverageRecordMap.get(appId).keySet().size()>0) {
			for (int device:mCoverageRecordMap.get(appId).keySet()) {
				int[] deviceMCovArray = mCoverageRecordMap.get(appId).get(device);
				if (appMCovArray==null) {
					appMCovArray = deviceMCovArray;
				}else {
					for (int i=0; i<deviceMCovArray.length; i++) {
						if (deviceMCovArray[i] > 0) {
							appMCovArray[i]+=deviceMCovArray[i];
						}
					}
				}
			}
			int mcovered = 0;
			if (appMCovArray != null) {
				for (int i=0; i<appMCovArray.length; i++) {
					if (appMCovArray[i] > 0) {
						mcovered++;
					}
				}
				return mcovered/(double)appMCovArray.length;
			}
		}
		return 0;
	}

	/**
	 * 获取指定应用指定端口的被测应用覆盖率
	 * @param appId	应用哈希值
	 * @param port	端口名称
	 * @return
	 */
	public static double getDeviceMethodCoverage(String appId, int port) {
		if (mCoverageRecordMap.get(appId)!=null) {
			int mcovered = 0;
			int[] deviceCovCoverage = mCoverageRecordMap.get(appId).get(port);
			if (deviceCovCoverage!=null) {
				for (int i=0; i<deviceCovCoverage.length; i++) {
					if (deviceCovCoverage[i] > 0) {
						mcovered++;
					}
				}
				return mcovered/(double)deviceCovCoverage.length;
			}
		}
		return 0;
	}


	private static int countLines(String file) {
		int totalNumberOfLines = 0;
		try {
			LineNumberReader lineReader = new LineNumberReader(new FileReader(Paths.get(file).toFile()));
			lineReader.skip(Long.MAX_VALUE);
			totalNumberOfLines = lineReader.getLineNumber();
			lineReader.close();
		} catch (Exception ex) {
			ex.printStackTrace();
		}
		return totalNumberOfLines;
	}

	public void serve(int port) throws IOException
	{
		System.out.println("Ella server starting at "+new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()));
		ServerSocket serverSocket = new ServerSocket(port, 0, java.net.InetAddress.getLoopbackAddress());
		new Timer().schedule(new TimerTask(){
				public void run(){
					long currentTime = System.currentTimeMillis();
					int size = workers.size();
					for(int i = 0; i < size; i++){
						Worker worker = workers.get(i);
						long lastReadTime = worker.lastReadTime;
						if(lastReadTime - currentTime > maxIdleTime){
							worker.stopWork();
							workers.remove(i);
						}
					}
				}
			}, 0L, 5000);


		while(true){
			try{
				Socket socket = serverSocket.accept();
				PushbackInputStream inputStream = new PushbackInputStream(new BufferedInputStream(socket.getInputStream()), BUFSIZE);
				byte[] buf = new byte[4];
				int offset = 0;
				do{
					int nRead = inputStream.read(buf, offset, 4-offset);
					if (nRead < 0) break;
					offset += nRead;
				}while(offset < 4);
				if(offset == 4){
					if (buf[0] == '\r' && buf[1] == '\n' && buf[2] == '\r' && buf[3] == '\n') {
						shutdown();
						break;
					} else {
						inputStream.unread(buf, 0, 4);
					}
				} else
					continue; // assert false;
				Worker worker = new Worker(socket, inputStream);
				workers.add(worker);
				worker.start();
			} catch(IOException e){
				throw new Error(e);
			}
		}

		serverSocket.close();
	}

	public void shutdown()
	{
		for(Worker worker : workers){
			worker.stop();
		}
		try{
			Thread.sleep(1000);
		}catch(InterruptedException e){
			throw new Error(e);
		}
		System.out.println("Ella server shutting down at "+new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()));
	}

	private class Worker extends Thread
	{
		private Socket socket;
		private PushbackInputStream inputStream;
		private boolean stop;
		private long lastReadTime;

		Worker(Socket socket, PushbackInputStream inputStream)
		{
			this.socket = socket;
			this.inputStream = inputStream;
			this.stop = false;
			this.lastReadTime = 0L;
			System.out.println("Accepting connection from "+socket.getRemoteSocketAddress()+" at "+new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss").format(new Date()));
		}

		public void run()
		{
			int read;
			byte[] buf = new byte[BUFSIZE];
			int offset = 0;

			try{
				do{
					read = inputStream.read(buf, offset, buf.length - offset);
					lastReadTime = System.currentTimeMillis();
					if(read > 0){
						int prefix = Math.min(3, offset);
						int splitByte = findMessageBreak(buf, offset - prefix, read + prefix);
						if(splitByte >= 0){
							String msg = new String(buf, 0, splitByte, java.nio.charset.Charset.forName("UTF-8"));
							collect(msg);
							int startOfNextMessage = splitByte+4;
							int totalRead = offset + read;
							if(startOfNextMessage < totalRead)
								inputStream.unread(buf, startOfNextMessage, totalRead - startOfNextMessage);
							offset = 0;
						} else {
							offset += read;
							if(offset > buf.length * 0.8){
								byte[] newbuff = new byte[buf.length*2];
								System.arraycopy(buf, 0, newbuff, 0, offset);
								buf = newbuff;
							}
						}
					}
				}while(read >= 0 && !stop);
			} catch(IOException e){
				System.out.println(e.getMessage());
				e.printStackTrace();
			} finally {
				try{
					inputStream.close();
					socket.close();
				}catch(IOException e){
					// throw new Error(e);
					e.printStackTrace();
				}
			}
		}

		void stopWork()
		{
			stop = true;
		}

		int findMessageBreak(byte[] buf, int offset, int num)
		{
			int splitbyte = Math.max(0, offset - 3);
			int end = offset + num;
			while (splitbyte + 3 < end) {
				if (buf[splitbyte] == '\r' && buf[splitbyte + 1] == '\n' && buf[splitbyte + 2] == '\r' && buf[splitbyte + 3] == '\n') {
					return splitbyte;
				}
				splitbyte++;
			}
			return -1;
		}

		class CoverageUpdate {
			private String id;
			public String getAppId() { return id; }
			private String cov;
			public String getData() { return cov; }
			private String stop;
			public boolean requestsStop() { return stop.equals("true"); }
			private String recorder;
			public String getRecorderName() { return recorder; }
		}

		public void collect(String data) throws IOException
		{
			BufferedWriter out = null;
			try {
				//System.out.println("request: "+sb.toString());
				CoverageUpdate covUpdate = (CoverageUpdate) new Gson().fromJson(data, CoverageUpdate.class);

				String appId = covUpdate.getAppId();
				String path = ellaOutDir + File.separator + appId;
				File dir = new File(path);
				if (!mCoverageRecordMap.containsKey(appId)) {
					// 获取该应用的方法总数
					int methodNumber = Server.countLines(path + File.separator + "covids");
					Map<Integer, int[]> deviceMCoverageMap = new HashMap<>();
					// 使用端口来区分device
					deviceMCoverageMap.put(socket.getLocalPort(), new int[methodNumber]);
					mCoverageRecordMap.put(appId, deviceMCoverageMap);
				}
				String traceId = appIdToTraceId.get(appId);
				if(traceId == null){
					dir.mkdir();

					DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss");
					traceId = dateFormat.format(new Date());
					appIdToTraceId.put(appId, traceId);
				}

				File datFile = new File(dir, "coverage.dat."+traceId);
				boolean append = datFile.exists();
				out = new BufferedWriter(new FileWriter(datFile, append));

				String d = covUpdate.getData();

				// write coverage info to file
				out.write("#" + System.currentTimeMillis() + "\n");
				out.write(d);

				// added by Ren
				String[] mIdList  = d.split("\n");
				for (String mStrId:mIdList) {
					int mid = Integer.parseInt(mStrId.trim());
					mCoverageRecordMap.get(appId).get(socket.getLocalPort())[mid] += 1;
				}

				if(covUpdate.requestsStop()){
					appIdToTraceId.remove(appId);
				}
				//logger.log(Level.INFO, "Upload succeeded");
			} catch(Exception e){
				e.printStackTrace();
				// throw new Error(e);
			} finally {
				if (out != null) {
					out.close();
				}
			}
		}
	}

}