package histogramMovies;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.*;

import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.*;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.n1analytics.paillier.EncryptedNumber;
import com.n1analytics.paillier.PaillierContext;
import com.n1analytics.paillier.PaillierPublicKey;

import encryption.Util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

/**
 * This is an example Hadoop Map/Reduce application. It reads the input movie
 * files and outputs a histogram showing how many movies fall in which category
 * of reviews. We make 8 reduce tasks for
 * 1-1.5,1.5-2,2-2.5,2.5-3,3-3.5,3.5-4,4-4.5,4.5-5. the reduce task counts all
 * reviews in the same bin.
 *
 * To run: bin/hadoop jar build/hadoop-examples.jar histogram_movies [-m
 * <i>maps</i>] [-r <i>reduces</i>] <i>in-dir</i> <i>out-dir</i>
 *
 * @author Faraz Ahmad
 */

public class HistogramMovies extends Configured implements Tool {

	private enum Counter {
		WORDS, VALUES
	}
	
	public static final Log LOG = LogFactory.getLog("org.apache.hadoop.examples.HistogramMovies");
	public static class NonSplittableTextInputFormat extends TextInputFormat {
		@Override
		protected boolean isSplitable(FileSystem fs, Path file) {
			return false;
		}
	}
	
	public static class MapClass extends MapReduceBase
			implements Mapper<LongWritable, Text, FloatWritable, IntWritable> {

		private final static IntWritable one = new IntWritable(1);
		//private final static float division = 0.5f;

		private PaillierPublicKey pub;
		private String hostName;
		private PaillierContext context;
		private String[] sumRatingsArray;
		private int[] totalReviewsArray;
		private int totalNumOfLines, count, portNumber = 44444;
		private EncryptedNumber zero;
		
		@Override
		public void configure(JobConf jobConf) {
			String pubKey = jobConf.get("pubKey");
			pub = new PaillierPublicKey(new BigInteger(pubKey));
			context = pub.createSignedContext();
	        hostName = jobConf.get("hostname");
	        totalNumOfLines = Integer.parseInt(jobConf.get("totalNumOfLines"));
	        sumRatingsArray = new String[totalNumOfLines];
	        totalReviewsArray = new int[totalNumOfLines];
	        zero = context.encrypt(0);
	    }
		
		public void map(LongWritable key, Text value, OutputCollector<FloatWritable, IntWritable> output,
				Reporter reporter) throws IOException {

			int movieIndex, reviewIndex;
			int totalReviews = 0;
			float outValue = 0f;
			String reviews = new String();
			String line = new String();
			String tok = new String();
			String ratingStr = new String();
			line = ((Text) value).toString();
			movieIndex = line.indexOf(":");
			EncryptedNumber sumRatings = zero, rating;
			if (movieIndex > 0) {
				reviews = line.substring(movieIndex + 1);
				StringTokenizer token = new StringTokenizer(reviews, ",");
				while (token.hasMoreTokens()) {
					tok = token.nextToken();
					reviewIndex = tok.indexOf("_");
					ratingStr = tok.substring(reviewIndex + 1);
					rating = Util.getAHCipher(ratingStr, context);
					sumRatings = context.add(rating, sumRatings);
					totalReviews++;
				}
				sumRatingsArray[count] = Util.getAHString(sumRatings);
				totalReviewsArray[count] = totalReviews;
				count++;
				if (count == totalNumOfLines) {
					try {
						Socket socket = new Socket(hostName, portNumber);
						ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
						for (int i = 0; i < count; i++) {
							out.writeObject(sumRatingsArray[i]);
							out.writeInt(totalReviewsArray[i]);
						}
						out.flush();
						ObjectInputStream in = new ObjectInputStream(socket.getInputStream());
						for (int i = 0; i < count; i++) {
							outValue = in.readFloat();
							output.collect(new FloatWritable(outValue), one);
							reporter.incrCounter(Counter.WORDS, 1);
						}
						socket.close();
					} catch (UnknownHostException e) {
						System.err.println("Don't know about host " + hostName);
						System.exit(1);
					} catch (IOException e) {
						System.err.println("Couldn't get I/O for the connection to " + hostName);
						System.exit(1);
					}
				}
			}
		}
	}

	public static class Reduce extends MapReduceBase
			implements Reducer<FloatWritable, IntWritable, FloatWritable, IntWritable> {

		public void reduce(FloatWritable key, Iterator<IntWritable> values,
				OutputCollector<FloatWritable, IntWritable> output, Reporter reporter) throws IOException {

			int sum = 0;
			while (values.hasNext()) {
				sum += ((IntWritable) values.next()).get();
				reporter.incrCounter(Counter.VALUES, 1);
			}
			output.collect(key, new IntWritable(sum));
		}

	}

	static void printUsage() {
		System.out.println("histogram_movies [-m <maps>] [-r <reduces>] <input> <output>");
		System.exit(1);
	}

	/**
	 * The main driver for histogram map/reduce program. Invoke this method to
	 * submit the map/reduce job.
	 * 
	 * @throws IOException
	 *             When there is communication problems with the job tracker.
	 */

	public int run(String[] args) throws Exception {
		
		JobConf conf = new JobConf(HistogramMovies.class);
		conf.set("pubKey", args[2]);
		conf.set("hostname", args[3]);
		conf.set("totalNumOfLines", args[4]);
		
		conf.setJobName("histogram_movies");
		conf.setInputFormat(NonSplittableTextInputFormat.class);
		conf.setOutputKeyClass(FloatWritable.class);
		conf.setOutputValueClass(IntWritable.class);

		conf.setMapperClass(MapClass.class);
		conf.setCombinerClass(Reduce.class);
		conf.setReducerClass(Reduce.class);

		List<String> other_args = new ArrayList<String>();
		for (int i = 0; i < args.length; ++i) {
			try {
				if ("-m".equals(args[i])) {
					conf.setNumMapTasks(Integer.parseInt(args[++i]));
				} else if ("-r".equals(args[i])) {
					conf.setNumReduceTasks(Integer.parseInt(args[++i]));
				} else {
					other_args.add(args[i]);
				}
			} catch (NumberFormatException except) {
				System.out.println("ERROR: Integer expected instead of " + args[i]);
				printUsage();
			} catch (ArrayIndexOutOfBoundsException except) {
				System.out.println("ERROR: Required parameter missing from " + args[i - 1]);
				printUsage(); // exits
			}
		}
		if (other_args.size() < 2) {
			System.out.println("ERROR: Wrong number of parameters: " + other_args.size() + " instead of 2.");
			printUsage();
		}

		FileInputFormat.setInputPaths(conf, new Path(other_args.get(0)));
		String outPath = new String(other_args.get(1));
		FileOutputFormat.setOutputPath(conf, new Path(outPath));

		Date startTime = new Date();
		System.out.println("Job started: " + startTime);

		JobClient.runJob(conf);

		Date end_time = new Date();
		System.out.println("Job ended: " + end_time);
		System.out.println("The job took " + (end_time.getTime() - startTime.getTime()) / 1000 + " seconds.");
		return 0;
	}

	public static void main(String[] args) throws Exception {
		int ret = ToolRunner.run(new HistogramMovies(), args);
		System.exit(ret);
	}
}
