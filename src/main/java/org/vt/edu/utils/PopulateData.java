package org.vt.edu.utils;

import static org.vt.edu.utils.Constant.RELATIVE_PATH;
import static org.vt.edu.utils.Constant.OUTPUT_PATH;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.yahoo.ycsb.ByteIterator;
import com.yahoo.ycsb.RestClient;

/**
 * This class reads a list of titles to be populated in the Wiki mirror. Then
 * for each title it performs the following action. 1. It reads the entry from
 * Text table corresponding with this title to fetch the data from first wiki
 * mirror. 2. It uses the MediaWiki edit API to create these pages in second
 * wiki mirror by filling them up with the data fetched in the previous step. 3.
 * Finally it verifies that these Pages are populated properly.
 * 
 * @author shivam.maharshi
 */
public class PopulateData {

	private String fromHostAd;
	private String toHostAd;
	private String toEndPoint;
	private String inputFile;
	private Connection fromCon = null;
	private PreparedStatement fromPst = null;
	private String fromDB = null;
	private String writeFailedUrlPath = null;
	private String readFailedUrlPath = null;
	private String missingUrlsPath = null;
	private String illegalUrlsPath = null;
	private long count;
	private List<String> writeFailedUrls = null;
	private List<String> readFailedUrls = null;
	private List<String> missingUrls = null;
	private List<String> illegalUrls = null;
	private RestClient rc = null;


	public PopulateData(String fromHostAd, String toHostAd, String toEndPoint, String inputFile, String fromDB,
			String writeFailedUrlPath, String readFailedUrlPath, String missingUrlsPath, String illegalUrlsPath, long readCount) {
		this.fromHostAd = fromHostAd;
		this.toHostAd = toHostAd;
		this.toEndPoint = toEndPoint;
		this.inputFile = inputFile;
		this.count = readCount;
		this.fromDB = fromDB;
		this.writeFailedUrlPath = writeFailedUrlPath;
		this.readFailedUrlPath = readFailedUrlPath;
		this.missingUrlsPath = missingUrlsPath;
		this.illegalUrlsPath = illegalUrlsPath;
		this.writeFailedUrls = new ArrayList<>();
		this.readFailedUrls = new ArrayList<>();
		this.missingUrls = new ArrayList<>();
		this.illegalUrls = new ArrayList<>();
		this.rc = RestClient.getClient();
	}

	private void initConnection() {
		try {
			Class.forName("com.mysql.jdbc.Driver");
			String url = String.format(
					"jdbc:mysql://%s:%d/%s?user=%s&password=%s&characterEncoding=utf-8&useUnicode=true",
					fromHostAd.split(":")[0], 3306, fromDB, "root", "root");
			fromCon = DriverManager.getConnection(url);
		} catch (SQLException | ClassNotFoundException ex) {
			ex.printStackTrace();
		}
	}

	public int write(String title, String data) {
		System.out.println("Posting data :: URL : " + title + " || Size : " + data.length());
		int responseCode;
		try {
			data = URLEncoder.encode(data, "UTF-8");
			String postData = new StringBuilder("section=new&title=").append(title).append("&appendtext=").append(data)
					.append("&token=%2B%5C").toString();
			responseCode = rc.httpPost("http://" + toHostAd + "/" + toEndPoint + "/api.php?action=edit&format=json",
					postData);
		} catch (RestClient.TimeoutException | IOException e) {
			responseCode = 500;
		}
		System.out.println("Posted data :: URL : " + title + " || Response code : " + responseCode);
		return responseCode;
	}

	private int read(String title) {
		System.out.println("Reading data :: URL : " + title);
		int responseCode;
		try {
			responseCode = rc.httpGet("http://" + toHostAd + "/" + toEndPoint + "/index.php/" + title,
					new HashMap<String, ByteIterator>());
		} catch (RestClient.TimeoutException | IOException e) {
			responseCode = 500;
		}
		return responseCode;
	}

	private String getText(String title) throws SQLException, IOException, IllegalArgumentException {
		System.out.println("Getting text data :: URL : " + title);
		String sql = "SELECT old_text FROM text t, page p WHERE p.page_latest = t.old_id AND p.page_title = ? LIMIT 1";
		fromPst = fromCon.prepareStatement(sql);
		fromPst.setString(1, URLDecoder.decode(title, "UTF-8"));
		ResultSet rs = fromPst.executeQuery();
		if (rs.next()) {
			Blob text = rs.getBlob(1);
			InputStream is = text.getBinaryStream();
			BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
			StringBuilder sb = new StringBuilder();
			String line;
			while ((line = br.readLine()) != null) {
				sb.append(line);
			}
			br.close();
			is.close();
			return sb.toString();
		}
		return null;
	}

	public void execute() {
		long start = System.currentTimeMillis();
		System.out.println("Executing script at : " + start);
		initConnection();
		List<String> titles = FileUtil.read(inputFile, 127129, count);
		for (String title : titles) {
			String data;
			try {
				data = getText(title);
				if(data==null) {
					missingUrls.add(title);
					continue;
				}
				// Retries once.
				int responseCode = write(title, data);
				if (responseCode == 500) {
					responseCode = write(title, data);
					if (responseCode == 500)
						writeFailedUrls.add(title);
				}
				// Retries once.
				responseCode = read(title);
				if (responseCode == 500) {
					responseCode = read(title);
					if (responseCode == 500)
						readFailedUrls.add(title);
				}
			} catch (SQLException | IOException e) {
				e.printStackTrace();
				missingUrls.add(title);
			} catch (IllegalArgumentException e) {
			  e.printStackTrace();
			  illegalUrls.add(title);
			}
		}
		FileUtil.write(writeFailedUrls, writeFailedUrlPath);
		FileUtil.write(readFailedUrls, readFailedUrlPath);
		FileUtil.write(missingUrls, missingUrlsPath);
		FileUtil.write(illegalUrls, illegalUrlsPath);
		System.out.println("Successfully finished populating data in : "
				+ ((System.currentTimeMillis() - start) / 60000) + " mins.");
	}

	public static void main(String[] args) {
	   //PopulateData pd = new PopulateData(null, "192.168.1.51:80", "testwiki", null, null, null, null, null, 0);
	   //int rc = pd.write("Google", "Google is the biggest software company.");
		String fromHostAd = "192.168.1.51";
		String toHostAd = "192.168.1.51";
		String toEndPoint = "sw";
		String inputFile = RELATIVE_PATH + "readtrace_full.txt";
		String fromDB = "wiki";
		String writeFailedUrlPath = OUTPUT_PATH + "writeFailedTitles.txt";
		String readFailedUrlPath = OUTPUT_PATH + "readFailedTitles.txt";
		String missingUrlsPath = OUTPUT_PATH + "missingTitles.txt";
		String illegalUrlsPath = OUTPUT_PATH + "illegalTitles.txt";
		long readCount = Integer.MAX_VALUE;
		int argLen = args.length;
		for (int i = 0; i < argLen; i++) {
			if (args[i].startsWith("-from=")) {
				fromHostAd = args[i].split("=")[1];
			} else if (args[i].startsWith("-to=")) {
				toHostAd = args[i].split("=")[1];
			} else if (args[i].startsWith("-toEndPoint=")) {
				toEndPoint = args[i].split("=")[1];
			} else if (args[i].startsWith("-inputFile=")) {
				inputFile = args[i].split("=")[1];
			} else if (args[i].startsWith("-fromDB=")) {
				fromDB = args[i].split("=")[1];
			} else if (args[i].startsWith("-writeFailedFilePath=")) {
				writeFailedUrlPath = args[i].split("=")[1];
			} else if (args[i].startsWith("-readFailedFilePath=")) {
				readFailedUrlPath = args[i].split("=")[1];
			} else if (args[i].startsWith("-missingUrlsFilePath=")) {
				missingUrlsPath = args[i].split("=")[1];
			} else if (args[i].startsWith("-illegalUrlsFilePath=")) {
        illegalUrlsPath = args[i].split("=")[1];
      } else if (args[i].startsWith("-readCount=")) {
				readCount = Long.valueOf(args[i].split("=")[1]);
			}
		}
		PopulateData pd = new PopulateData(fromHostAd, toHostAd, toEndPoint, inputFile, fromDB, writeFailedUrlPath,
				readFailedUrlPath, missingUrlsPath, illegalUrlsPath, readCount);
		pd.execute();
	}

}