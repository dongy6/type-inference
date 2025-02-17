package defuse;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;

import soot.BooleanType;
import soot.PackManager;
import soot.Transform;
import soot.Unit;
import soot.Value;
import soot.ValueBox;
import soot.jimple.AssignStmt;
import soot.jimple.BinopExpr;
import soot.jimple.ConditionExpr;
import soot.jimple.EqExpr;
import soot.jimple.IfStmt;
import soot.jimple.NeExpr;
import soot.jimple.NullConstant;
import soot.jimple.internal.ImmediateBox;
import soot.jimple.internal.JimpleLocalBox;
import soot.jimple.internal.VariableBox;

public class RDanalysis {
	
	private Reference[] mapSources, reduceSources;
	private Map<Value, Set<Unit>> mapDefUseChains, reduceDefUseChains;
	private Map<Value, Set<Unit>> defUseChains;
	private Map<Value, Reference> sensitiveValues;
	private Set<String> keyBucket, detBucket, opeBucket, ignoreBucket, mathBucket;
	private String detIgnore;
	private RDTransformer transformer;
	private Set<Value> convertedValues;
	private ArrayList<Integer> mapLoops, reduceLoops;
	private Set<Integer> mapConversions, reduceConversions;
	private Map<Value, Map<Integer, String>> conversionCount;

	public RDanalysis(RDTransformer transformer) {
		this.transformer = transformer;
		mapDefUseChains = transformer.getMapDefUseChains();
		reduceDefUseChains = transformer.getReduceDefUseChains();
		defUseChains = new LinkedHashMap<>();
		defUseChains.putAll(mapDefUseChains);
		defUseChains.putAll(reduceDefUseChains);
		
		mapLoops = transformer.getMapLoops();
		reduceLoops = transformer.getReduceLoops();
		mapConversions = new HashSet<>();
		reduceConversions = new HashSet<>();

		conversionCount = new HashMap<>();

		sensitiveValues = new LinkedHashMap<>();
		convertedValues = new HashSet<>();
		keyBucket = new HashSet<>();
		detBucket = new HashSet<>();
		opeBucket = new HashSet<>();
		ignoreBucket = new HashSet<>();
		mathBucket = new HashSet<>();
		
		mathBucket.add("<java.lang.Math: double sqrt(double)>");
		mathBucket.add("<java.lang.Math: double floor(double)>");
		//mathBucket.add("<java.lang.Math: int round(float)>");
		
		detIgnore = "<java.lang.String: boolean equals(java.lang.Object)>(\"\")";
		
		keyBucket.add("<org.apache.hadoop.mapred.OutputCollector: void collect(java.lang.Object,java.lang.Object)>");
		keyBucket.add("<org.apache.hadoop.mapreduce.TaskInputOutputContext: void write(java.lang.Object,java.lang.Object)");
		keyBucket.add("<org.apache.hadoop.mapreduce.Mapper$Context: void write(java.lang.Object,java.lang.Object)>");
		
		detBucket.add("<java.lang.String: boolean equals(java.lang.Object)>");
		detBucket.add("<java.util.List: boolean contains(java.lang.Object)>");
		detBucket.add("<java.util.Set: boolean contains(java.lang.Object)>");
		detBucket.add("<java.util.HashSet: boolean add(java.lang.Object)>");
		detBucket.add("<java.util.Set: boolean add(java.lang.Object)>");
		
		opeBucket.add("<java.util.Collections: void sort(java.util.List)>");
		opeBucket.add("<java.util.Arrays: void sort(java.lang.Object[])>");
		
		ignoreBucket.add("<java.util.List: int size()>");
		ignoreBucket.add("<java.lang.String: int length()>");
		ignoreBucket.add("<java.lang.String: int indexOf(java.lang.String)>");
		ignoreBucket.add("<java.util.Map: java.lang.Object get(java.lang.Object)>");
	}


	private void propagateMap(String[] mSources) {
		// propagate sensitivity; BFS; add children
		int feskhj = 10;

		BufferedWriter writer = null;
		try {
			writer = new BufferedWriter(new FileWriter("propogateMap"));
		} catch (IOException e) {
			e.printStackTrace();
		}


		mapSources = new Reference[mSources.length];
		for (Value defValue : mapDefUseChains.keySet()) {
			try {
				writer.write(String.format("%s\n", defValue.toString()));
				writer.flush();
			} catch (IOException e) {
				e.printStackTrace();
			}

			for (int i = 0; i < mSources.length; i++) {
				if (mSources[i].equals(defValue.toString())) {
					Reference ref = sensitiveValues.getOrDefault(defValue, new Reference(defValue));
					sensitiveValues.put(defValue, ref);
					ref.setInMap(true);
					mapSources[i] = ref;
				}
			}
		}
		try {
			writer.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		scanDefUseChains(mapDefUseChains);
	}

	private void propagateReduce(String[] rSources) {
		// propagate sensitivity; BFS; add children
		reduceSources = new Reference[rSources.length];
		for (Value defValue : reduceDefUseChains.keySet()) {
			for (int i = 0; i < rSources.length; i++) {
				if (rSources[i].equals(defValue.toString())) {
					Reference ref = sensitiveValues.getOrDefault(defValue, new Reference(defValue));
					sensitiveValues.put(defValue, ref);
					reduceSources[i] = ref;
				}
			}
		}
		scanDefUseChains(reduceDefUseChains);
	}

	private void scanDefUseChains(Map<Value, Set<Unit>> defUseChains) {
		Queue<Value> queue = new LinkedList<>();
		Set<Value> visited = new HashSet<>();
		for (Value source : sensitiveValues.keySet())
			queue.add(source);
		while (!queue.isEmpty()) {
			Value defValue = queue.remove();
			if (visited.contains(defValue))
				continue;
			Reference defRef = sensitiveValues.get(defValue);
			for (Unit useUnit : defUseChains.getOrDefault(defValue, new HashSet<>())) {
				boolean shouldIgnore = false;
				for (String ignore : ignoreBucket)
					if (useUnit.toString().contains(ignore)) {
						shouldIgnore = true;
						break;
					}
				if (shouldIgnore) continue;
				for (Object valueBox : useUnit.getUseAndDefBoxes()) {
					if (valueBox instanceof VariableBox || valueBox instanceof JimpleLocalBox
							|| (useUnit.toString().contains("<java.util.List: java.lang.Object[] toArray(java.lang.Object[])>")
									&& valueBox instanceof ImmediateBox)) {
						Value value = ((ValueBox) valueBox).getValue();
						if (value.getType() instanceof BooleanType) continue;
						sensitiveValues.put(value, sensitiveValues.getOrDefault(value, new Reference(value)));
						sensitiveValues.get(value).setInMap(defRef.isInMap());
						queue.add(value);
						if (value != defValue)
							defRef.addChild(value);
					}
				}
			}
			visited.add(defValue);
		}
	}

	private Set<Reference> getMapValues() {
		Set<Reference> mapValues = new HashSet<>();
		for (Value defValue : sensitiveValues.keySet()) {
			for (Unit useUnit : mapDefUseChains.getOrDefault(defValue, new HashSet<>())) {
				for (String keyUnitStr : keyBucket) {
					if (useUnit.toString().contains(keyUnitStr)) {
						String useUnitStr = useUnit.toString();
						int indexBegin = useUnitStr.lastIndexOf(',') + 2;
						int indexEnd = useUnitStr.lastIndexOf(')');
						if (useUnitStr.substring(indexBegin, indexEnd).equals(defValue.toString())) {
							Reference ref = sensitiveValues.get(defValue);
							mapValues.add(ref);
						}
					}
				}
			}
		}
		return mapValues;
	}

	private void print_op_line(Value defValue, Value op1, Value op2, String symbol) {
		String print_str = String.format("Analyzing this line: %s = %s %s %s", defValue.toString(), op1.toString(), symbol, op2.toString());
		System.out.println(print_str);
	}

	private void print_conversion(Value defValue, String from, String to, int line) {
		Map<Integer, String> tmp;
		if(conversionCount.containsKey(defValue)) {
			tmp = conversionCount.get(defValue);
		}
		else {
			tmp = new HashMap<>();
		}
		tmp.put(line, String.format("From %s To %s", from, to));
		conversionCount.put(defValue, tmp);
		String print_str = String.format("Value %s must be converted from %s to %s", defValue.toString(), from, to);
		System.out.println(print_str);
	}

	@SuppressWarnings("Duplicates")
	private void addOperations() {
		// Add operations and check conversions
		for (Value defValue : defUseChains.keySet()) {
			Reference ref = sensitiveValues.get(defValue);
			if (ref == null) continue;
			Set<Integer> conversions = ref.isInMap() ? mapConversions : reduceConversions;
			for (Unit useUnit : defUseChains.get(defValue)) {
				for (String detUnitStr : detBucket) {
					if (useUnit.toString().contains(detUnitStr))
						if (!useUnit.toString().contains(detIgnore))
							ref.addOperation("DET");
				}
				for (String keyUnitStr : keyBucket) {
					if (useUnit.toString().contains(keyUnitStr)) {
						String useUnitStr = useUnit.toString();
						int indexBegin = useUnitStr.lastIndexOf('(') + 1;
						int indexEnd = useUnitStr.lastIndexOf(',');
						if (useUnitStr.substring(indexBegin, indexEnd).equals(defValue.toString()))
							ref.addOperation("DET");
					}
				}
				if (useUnit.toString().contains("<java.util.Map: java.lang.Object put(java.lang.Object,java.lang.Object)>")) {
					String useUnitStr = useUnit.toString();
					int indexBegin = useUnitStr.lastIndexOf('(') + 1;
					int indexEnd = useUnitStr.lastIndexOf(',');
					if (useUnitStr.substring(indexBegin, indexEnd).equals(defValue.toString()))
						ref.addOperation("DET");
				}
				for (String opeUnitStr : opeBucket) {
					if (useUnit.toString().contains(opeUnitStr)) {
						ref.addOperation("OPE");
					}
				}
				for (String opeUnitStr : mathBucket) {
					if (useUnit.toString().contains(opeUnitStr)) {
						//System.out.println("Conversion: " + useUnit.getJavaSourceStartLineNumber() + "-" + useUnit);
						conversions.add(useUnit.getJavaSourceStartLineNumber());
						convertedValues.add(defValue);
						ref.clearOperations();
						clearOperations(ref);
					}
				}
				if (useUnit instanceof IfStmt) {
					Value condition = ((IfStmt) useUnit).getCondition();
					if (condition instanceof ConditionExpr) {
						ConditionExpr conditionExpr = (ConditionExpr) condition;
						if ((conditionExpr.getOp1() == defValue && !(conditionExpr.getOp2() instanceof NullConstant))
								|| (conditionExpr.getOp2() == defValue && !(conditionExpr.getOp1() instanceof NullConstant))) {
							if (condition instanceof NeExpr || condition instanceof EqExpr) {
								ref.addOperation("DET");
							} else {
								ref.addOperation("OPE");
							}
						}
					}
				} else if (useUnit instanceof AssignStmt) {
						Value rhsOp = ((AssignStmt) useUnit).getRightOp();

						if (rhsOp instanceof BinopExpr) {
							String symbol = ((BinopExpr) rhsOp).getSymbol();
							Value op1 = ((BinopExpr) rhsOp).getOp1();
							Value op2 = ((BinopExpr) rhsOp).getOp2();
							print_op_line(defValue, op1, op2, symbol);
							switch (symbol) {
							case " + ":
							case " - ":
								if (ref.contains("MH")) {
									int line_num = useUnit.getJavaSourceStartLineNumber();
									print_conversion(defValue,"MH", "AH", line_num);
									conversions.add(line_num);
									convertedValues.add(defValue);
									ref.removeOperation("MH");
									removeOperations(ref, "MH");
								}
								ref.addOperation("AH");
								addOperations(ref, "AH");
								break;
							case " * ":
								if (sensitiveValues.containsKey(op1) && sensitiveValues.containsKey(op2)) {
									if (ref.contains("AH")) {
										int line_num = useUnit.getJavaSourceStartLineNumber();
										print_conversion(defValue,"AH", "MH", line_num);
										conversions.add(line_num);
										convertedValues.add(defValue);
										ref.removeOperation("AH");
										removeOperations(ref, "AH");
									}
									ref.addOperation("MH");
									addOperations(ref, "MH");
								} else {
									if (ref.contains("MH")) {
										int line_num = useUnit.getJavaSourceStartLineNumber();
										print_conversion(defValue,"AH", "MH", line_num);
										conversions.add(line_num);
										convertedValues.add(defValue);
										ref.removeOperation("MH");
										removeOperations(ref, "MH");
									}
									ref.addOperation("AH");
									addOperations(ref, "AH");
								}
								break;
							case " / ":
								if (sensitiveValues.containsKey(op1) && sensitiveValues.containsKey(op2)) {
									conversions.add(useUnit.getJavaSourceStartLineNumber());
									convertedValues.add(defValue);
									ref.clearOperations();
									clearOperations(ref);
								} else {
									if (ref.contains("MH")) {
										int line_num = useUnit.getJavaSourceStartLineNumber();
										print_conversion(defValue,"AH", "MH", line_num);
										conversions.add(line_num);
										convertedValues.add(defValue);
										ref.removeOperation("MH");
										removeOperations(ref, "MH");
									}
									ref.addOperation("AH");
									addOperations(ref, "AH");
								}
								break;
							case " cmpl ":
							case " cmpg ":
							case " >= ":
								if (ref.contains("MH")) {
									conversions.add(useUnit.getJavaSourceStartLineNumber());
									convertedValues.add(defValue);
									ref.addOperation("OPE");
									ref.removeOperation("MH");
									removeOperations(ref, "MH");
								}
								if (ref.contains("AH")) {
									conversions.add(useUnit.getJavaSourceStartLineNumber());
									convertedValues.add(defValue);
									ref.addOperation("OPE");
									ref.removeOperation("AH");
									removeOperations(ref, "AH");
								}
							}
						}
				}
			}
		}
	}

	private void clearOperations(Reference ref) {
		for (Value child : ref.getChildren()) {
			if (sensitiveValues.get(child).clearOperations())
				clearOperations(sensitiveValues.get(child));
		}
	}
	
	private void addOperations(Reference ref, String ope) {
		for (Value child : ref.getChildren()) {
			if (sensitiveValues.get(child).addOperation(ope))
				addOperations(sensitiveValues.get(child), ope);
		}
	}
	
	private void removeOperations(Reference ref, String ope) {
		for (Value child : ref.getChildren()) {
			if (sensitiveValues.get(child).contains(ope)) {
				sensitiveValues.get(child).removeOperation(ope);
				removeOperations(sensitiveValues.get(child), ope);
			}
		}
	}
	
	private void propagateOperations() {
		// propagate operations bottom up
		boolean changed = true;
		while (changed) {
			changed = false;
			for (Value defValue : sensitiveValues.keySet()) {
				Reference defRef = sensitiveValues.get(defValue);
				for (Value child : defRef.getChildren()) {
					if (convertedValues.contains(child)) continue;
					for (String operation : sensitiveValues.get(child).getOperations()) {
						if (defRef.addOperation(operation))
							changed = true;
					}
				}
			}
		}
	}

	public Map<Value, Reference> getSensitiveValues() {
		return sensitiveValues;
	}
	
	public Reference[] getSources() {
		return mapSources;
	}
	
	private String findRegion(Set<Integer> conversions, ArrayList<Integer> loops, String className) {
		int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
		for(Map.Entry<Value, Map<Integer, String>> entry : conversionCount.entrySet()) {
			String print_str = String.format("%s must be converted %d times", entry.getKey().toString(), entry.getValue().keySet().size());
			System.out.println(print_str);
			Map<Integer, String> value_map = entry.getValue();
			for(Map.Entry<Integer, String> value_entry : value_map.entrySet()) {
				print_str = String.format("\tLine %d: %s",value_entry.getKey(), value_entry.getValue());
				System.out.println(print_str);
			}
		}

		for (int conversion : conversions) {
			System.out.println(String.format("Conversion on line: %d", conversion));
			boolean inLoop = false;
			for (int i = 0; i < loops.size() - 1; i++) {
				int start = loops.get(i);
				int end = loops.get(i + 1);
				if (conversion > start && conversion < end) {
					min = Math.min(min, start);
					max = Math.max(max, end - 1);
					inLoop = true;
				}
			}
			if (!inLoop) {
				min = Math.min(min, conversion);
				max = Math.max(max, conversion);
			}
		}
		return min == Integer.MAX_VALUE ? null : (min + "-" + max + " in " + className);
	}

	public String analyze(String[] mSources, String[] rSources) {
		propagateMap(mSources);
		if (rSources.length == 0) { // no direct input; link values
			Value reduceValue = transformer.getReduceValue();
			if (reduceValue != null) {
				Set<Reference> mapValues = getMapValues();
				// add all uses of reduce value to the use set of map value
				for (Reference mapValue : mapValues) {
					mapDefUseChains.get(mapValue.getValue()).addAll(reduceDefUseChains.get(reduceValue));
				}
				// propagate sensitivity
				this.scanDefUseChains(defUseChains);
				// all variables become map variables; leave it for now
			}
		} else {
			propagateReduce(rSources);
			// link values
			for (int i = 0; i < mapSources.length; i++) {
				if (reduceSources[i] == null) {
					continue;
				}
				else {
					mapSources[i].addChild(reduceSources[i].getValue());
				}

			}
		}
		this.addOperations();
		this.propagateOperations();
		Set<Integer> conversions;
		ArrayList<Integer> loops;
		String className;
		if (!mapConversions.isEmpty()) {
			conversions = mapConversions;
			loops = mapLoops;
			className = transformer.getMapClass();
		} else {
			conversions = reduceConversions;
			loops = reduceLoops;
			className = transformer.getReduceClass();
		}
		return findRegion(conversions, loops, className);
	}

	public static void main(String[] args) {
		ArrayList<String> arguments = new ArrayList<>();
		String mapSources = "", reduceSources = "";
		for (int i = 0; i < args.length; i++) {
			if (args[i].equals("-mapSources")) {
				mapSources = args[++i];
			} else if (args[i].equals("-reduceSources")) {
				reduceSources = args[++i];
			} else {
				arguments.add(args[i]);
			}
		}
		String[] sootArgs = new String[] {"-allow-phantom-refs", "--keep-line-number",
				"-p", "jb", "use-original-names:true", "-f", "jimple"};
		Collections.addAll(arguments, sootArgs);
		RDTransformer transformer = new RDTransformer();
		PackManager.v().getPack("jtp").add(new Transform("jtp.rd", transformer));
		soot.Main.main(arguments.toArray(new String[0]));

		String[] mSources = mapSources.split(":");
		String[] rSources = reduceSources.equals("") ? new String[0] : reduceSources.split(":");
		RDanalysis analysis = new RDanalysis(transformer);

		int llfl = 94;

		String region = analysis.analyze(mSources, rSources);
		System.out.println();
		for (Reference source : analysis.getSources()) {
			Set<String> operations = source.getOperations();
			if (operations.isEmpty())
				System.out.println(source.getValue() + ": [RND]");
			else
				System.out.println(source.getValue() + ": " + operations);
		}
		if (region != null)
			System.out.println("Region should be extracted: " + region);
	}

}
