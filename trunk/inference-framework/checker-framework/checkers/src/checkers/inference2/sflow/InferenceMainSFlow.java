/**
 * 
 */
package checkers.inference2.sflow;

import static com.esotericsoftware.minlog.Log.DEBUG;
import static com.esotericsoftware.minlog.Log.info;
import static com.esotericsoftware.minlog.Log.warn;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import checkers.inference2.Constraint;
import checkers.inference2.ConstraintSolver;
import checkers.inference2.InferenceChecker;
import checkers.inference2.MaximalTypingExtractor;
import checkers.inference2.SetbasedSolver;
import checkers.inference2.TypingExtractor;
import checkers.util.CheckerMain;

import com.sun.tools.javac.main.Main;

/**
 * Those helper methods are from {@link CheckerMain}
 * @author huangw5
 *
 */
public class InferenceMainSFlow {

    private static final String VERSION = "1";
    
    public static final String outputDir = "infer-output";
    
    private static InferenceMainSFlow inferenceMain = null;

    protected boolean needCheck = true;
    
    protected InferenceChecker checker; 
    
    protected InferenceMainSFlow() {
    	checker = null;
    }
    
    public static InferenceMainSFlow getInstance() {
        synchronized (InferenceMainSFlow.class) {
            if (inferenceMain == null) {
                String mainClass = System.getProperty("mainClass");
                if (mainClass != null) {
                    try {
                        inferenceMain = (InferenceMainSFlow) Class.forName(mainClass).newInstance();
                    } catch (Exception e) {
                        e.printStackTrace();
                        System.exit(1);
                    }
                }
                else {
                    inferenceMain = new InferenceMainSFlow();
                }
                inferenceMain.needCheck = (System.getProperty("noCheck") == null);
            }
        }
    	return inferenceMain;
    }
    
    public static void destroy() {
    	inferenceMain = null;
    }

	public InferenceChecker getInferenceChcker() {
		return checker;
	}

	public void setInferenceChcker(InferenceChecker inferenceChcker) {
		this.checker = inferenceChcker;
	}
	
	private enum InferType {
		REIM,
		SFLOW
	}
	
	private boolean inferImpl(List<String> argList, PrintWriter out, InferType itype) {
		com.sun.tools.javac.main.Main main = new com.sun.tools.javac.main.Main("javac", out);
        if (main.compile(argList.toArray(new String[0])) != Main.Result.OK)
        	return false;

		if (checker.getConstraints().isEmpty()) {
			warn(checker.getName(), "No constraints generated.");
			return false;
		}
		// output constraints
		if (DEBUG) {
			try {
				PrintWriter pw = new PrintWriter(InferenceMainSFlow.outputDir
						+ File.separator + checker.getName() + "-constraints.log");
                for (Constraint c : checker.getConstraints()) {
                    pw.println(c.toString());
                }
				pw.close();
				
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		ConstraintSolver solver;
		if (itype == InferType.SFLOW) {
			solver = new SFlowConstraintSolver((SFlowChecker) checker);
		} else {
			solver = new SetbasedSolver(checker);
		}
        Set<Constraint> setErrors = solver.solve();
		if (!setErrors.isEmpty()) {
			for (Constraint c : setErrors) {
				System.out.println(c);
			}
			info(checker.getName(), setErrors.size() + " error(s) in the set-based solution.");
			return false;
		}
		info(checker.getName(), "Extracting a concete typing...");
		TypingExtractor extractor;
		if (itype == InferType.SFLOW) {
			extractor = new SFlowTypingExtractor(checker);
		} else {
			extractor = new MaximalTypingExtractor(checker);
		}
		List<Constraint> typeErrors = extractor.extract();
		info(checker.getName(), "Finish extracting typing.");
		try {
			PrintWriter pw = new PrintWriter(InferenceMainSFlow.outputDir
					+ File.separator + checker.getName() + "-result.csv");
			checker.printResult(pw);
			pw.close();
			
		} catch (Exception e) {
			e.printStackTrace();
		}
		if (!typeErrors.isEmpty()) {
			for (Constraint c : typeErrors)  {
				System.out.println(c);
			}
			info(checker.getName(), typeErrors.size() + " error(s) in the concrete typing.");
			return false;
		}
		return true;
	}

	public boolean infer(String[] args, String jdkBootPaths, PrintWriter out) {
		for (String path : jdkBootPaths.split(File.pathSeparator)) {
			if (!path.equals("") && !new File(path).exists()) 
				throw new RuntimeException("Cannot find the boot jar: " + path);
		}
		
		System.setProperty("sun.boot.class.path",
				jdkBootPaths + ":" + System.getProperty("sun.boot.class.path"));
		
		List<String> argList = new ArrayList<String>(args.length + 10);
        String reimCheckerPath = "checkers.inference.reim.ReimChecker";
        String reimFlowCheckerPath = "";
        int processorIndex = -1;
        for (int i = 0; i < args.length; i++) {
        	String arg = args[i];
        	// Intercept the processor and replace it with ReimChecker
        	if (arg.equals("-processor")) {
        		argList.add(arg);
        		argList.add(reimCheckerPath);	
				processorIndex = ++i;
				reimFlowCheckerPath = args[processorIndex];
        	}
        	else
	        	argList.add(arg);
        }
        // Add arguments
		argList.add("-Xbootclasspath/p:" + jdkBootPaths);
		argList.add("-proc:only");
		argList.add("-Ainfer");
		argList.add("-Awarns");
        
        for (String arg : args) 
        	argList.add(arg);
        
        info("Reim", "Generating constraints...");
        if (!inferImpl(argList, out, InferType.REIM)) {
        	return false;
        }
        info("SFlow", "Generating constraints...");
		// First we need to revert the processor
		argList.set(processorIndex, reimFlowCheckerPath);
		return inferImpl(argList, out, InferType.SFLOW);
	}
	
  public boolean check(String[] args, String jdkBootPaths, PrintWriter out) {
      System.setProperty("sun.boot.class.path",
          jdkBootPaths + ":" + System.getProperty("sun.boot.class.path"));
      List<String> argList = new ArrayList<String>(args.length + 10);
      argList = new ArrayList<String>(args.length + 10);
      for (String arg : args) 
        argList.add(arg);

      argList.add("-Xbootclasspath/p:" + jdkBootPaths);
      argList.add("-Awarns");
      argList.add("-proc:only");
      com.sun.tools.javac.main.Main main = new com.sun.tools.javac.main.Main("javac", out);
      if (main.compile(argList.toArray(new String[0])) != Main.Result.OK) {
        return false;
      }
      return true;
  }
	
    
	/**
	 * @param args
	 */
	public static void main(String[] args) {
		String jdkBootPaths = findPathJar(InferenceMainSFlow.class);
		if (jdkBootPaths == "" || !jdkBootPaths.contains("jdk.jar")) {
			String customJDK = System.getProperty("jdk");
			if (customJDK != null)
				jdkBootPaths += File.pathSeparator + customJDK;
			else
				jdkBootPaths += File.pathSeparator + jdkJar();
		}
		String bootStr = "-Xbootclasspath/p:";
		for (String arg : args) {
			if (arg.startsWith(bootStr)) {
				jdkBootPaths += File.pathSeparator
						+ arg.substring(bootStr.length());
				break;
			}
		}
		info("main", "Boot path: " + jdkBootPaths);
		if (outputDir != null && outputDir.compareTo(".") != 0) {
			File dir = new File(outputDir);
			if (!dir.exists()) {
				dir.mkdir();
			}
		}

		InferenceMainSFlow inferenceMain = InferenceMainSFlow.getInstance();
		long startTime = System.currentTimeMillis();
		if (!inferenceMain.infer(args, jdkBootPaths, new PrintWriter(
				System.err, true))) {
			return;
		}

		info("main", "Inference finished");
		info("main", "inference_time:\t"
				+ String.format("%6.1f seconds",
						(float) (System.currentTimeMillis() - startTime) / 1000));

		if (inferenceMain.needCheck) {
			startTime = System.currentTimeMillis();
			inferenceMain.check(args, jdkBootPaths, new PrintWriter(System.err,
					true));
			info("main", "Checking finished");
			info("main", "checking_time:\t"
					+ String.format(
							"%6.1f seconds",
							(float) (System.currentTimeMillis() - startTime) / 1000));
		} else {
			info("main", "Skip checking");
		}
	}
	
	
    protected static File tempJDKPath() {
        String userSupplied = System.getProperty("jsr308.jdk");
        if (userSupplied != null)
            return new File(userSupplied);

        String tmpFolder = System.getProperty("java.io.tmpdir");
        File jdkFile = new File(tmpFolder, "jdk-" + VERSION + ".jar");
        return jdkFile;
    }
	
	/** returns the path to annotated JDK */
    protected static String jdkJar() {
        // case 1: running from binary
        String thisJar = findPathJar(InferenceMainSFlow.class);
        File potential = new File(new File(thisJar).getParentFile(), "jdk.jar");
        if (potential.exists()) {
            //System.out.println("from adjacent jdk.jar");
            return potential.getPath();
        }

        // case 2: there was a temporary copy
        File jdkFile = tempJDKPath();
        //System.out.println(jdkFile);
        if (jdkFile.exists()) {
            //System.out.println("From temporary");
            return jdkFile.getPath();
        }

        // case 3: extract zipped jdk.jar
        try {
            extractFile(thisJar, "jdk.jar", jdkFile);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        if (jdkFile.exists()) {
            //System.out.println("Extracted jar");
            return jdkFile.getPath();
        }

        throw new AssertionError("Couldn't find annotated JDK");
    }

    protected static void extractFile(String jar, String fileName, File output) throws Exception {
        int BUFFER = 2048;

        File jarFile = new File(jar);
        ZipFile zip = new ZipFile(jarFile);

        ZipEntry entry = zip.getEntry(fileName);
        assert !entry.isDirectory();

        BufferedInputStream is = new BufferedInputStream(zip.getInputStream(entry));
        int currentByte;
        // establish buffer for writing file
        byte data[] = new byte[BUFFER];

        // write the current file to disk
        FileOutputStream fos = new FileOutputStream(output);
        BufferedOutputStream dest = new BufferedOutputStream(fos, BUFFER);

        // read and write until last byte is encountered
        while ((currentByte = is.read(data, 0, BUFFER)) != -1) {
            dest.write(data, 0, currentByte);
        }
        dest.flush();
        dest.close();
        is.close();
    }

    /**
     * Find the jar file containing the annotated JDK (i.e. jar containing
     * this file
     */
    protected static String findPathJar(Class<?> context) throws IllegalStateException {
        if (context == null) context = CheckerMain.class;
        String rawName = context.getName();
        String classFileName;
        /* rawName is something like package.name.ContainingClass$ClassName. We need to turn this into ContainingClass$ClassName.class. */ {
            int idx = rawName.lastIndexOf('.');
            classFileName = (idx == -1 ? rawName : rawName.substring(idx+1)) + ".class";
        }

        String uri = context.getResource(classFileName).toString();
        if (uri.startsWith("file:")) throw new IllegalStateException("This class has been loaded from a directory and not from a jar file.");
        if (!uri.startsWith("jar:file:")) {
            int idx = uri.indexOf(':');
            String protocol = idx == -1 ? "(unknown)" : uri.substring(0, idx);
            throw new IllegalStateException("This class has been loaded remotely via the " + protocol +
                    " protocol. Only loading from a jar on the local file system is supported.");
        }

        int idx = uri.indexOf('!');
        //As far as I know, the if statement below can't ever trigger, so it's more of a sanity check thing.
        if (idx == -1) throw new IllegalStateException("You appear to have loaded this class from a local jar file, but I can't make sense of the URL!");

        try {
            String fileName = URLDecoder.decode(uri.substring("jar:file:".length(), idx), Charset.defaultCharset().name());
            return new File(fileName).getAbsolutePath();
        } catch (UnsupportedEncodingException e) {
            throw new InternalError("default charset doesn't exist. Your VM is borked.");
        }
    }
}
