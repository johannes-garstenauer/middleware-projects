package mw.hybridcloud;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MWTweetReader {
	
	private static final String MW_TIMETAG = "i4mwtime";
	private static final String MW_NAMETAG = "i4mwfullname";
	private static final String MW_USERTAG = "i4mwusername";
	private static final String MW_TWEETTAG = "i4mwtweet";
	
	private final String tweetsFile;
	
	private BufferedReader b;
	
	public MWTweetReader(String tweetsFile) {
		this.tweetsFile = tweetsFile;
		initFileReader();
	}
	
	private void initFileReader() {
		try {
			b = new BufferedReader(new FileReader(this.tweetsFile));
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(-1);
		}
	}
	
	private String getTextBetweenTags(String line, String tag) {
		if (line == null || tag == null) {
			System.err.println("Invalid line. Skipping...");
			return null;
		}
		
		String startTag = "<" + tag + ">";
		String endTag = "</" + tag + ">";
		String retStr = "";
		
		int posStart = line.indexOf(startTag);
		int posEnd = line.indexOf(endTag);

		if (posEnd > posStart && posEnd <= line.length())
			retStr = line.substring(posStart+startTag.length(), posEnd);
		
		return retStr;
	}
	
	public List<MWTweet> getNextTweetsBatch(int batchSize) {
		List<String> tweetLines = getTweetLines(batchSize);
		if (tweetLines == null) return null;

		return parseTweetLines(tweetLines);
	}

	private List<MWTweet> parseTweetLines(List<String> tweetLines) {
		List<MWTweet> batch = new ArrayList<>();
		for (String tweetLine : tweetLines) {
			MWTweet tweet = new MWTweet();
			// Parse tweet
			tweet.time = getTextBetweenTags(tweetLine, MW_TIMETAG);
			tweet.name = getTextBetweenTags(tweetLine, MW_NAMETAG);
			tweet.username = getTextBetweenTags(tweetLine, MW_USERTAG);
			tweet.tweet = getTextBetweenTags(tweetLine, MW_TWEETTAG);

			batch.add(tweet);
		}
		return batch;
	}

	private synchronized List<String> getTweetLines(int batchSize) {
		List<String> tweetLines = new ArrayList<>();
		while (tweetLines.size() < batchSize) {
			String tweetLine;
			try {
				tweetLine = b.readLine();
			} catch (IOException e) {
				e.printStackTrace();
				return null;
			}
			if (tweetLine == null) {
				initFileReader();
				System.out.println("Starting from the beginning...");
				continue;
			}
			tweetLines.add(tweetLine);
		}
		return tweetLines;
	}

}
