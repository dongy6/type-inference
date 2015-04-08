/**
 * 
 */
package checkers.inference.sflow;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.SupportedOptions;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;

import checkers.basetype.BaseTypeVisitor;
import checkers.inference.Constraint;
import checkers.inference.DescriptorUtil;
import checkers.inference.InferenceChecker;
import checkers.inference.InferenceMain;
import checkers.inference.InferenceUtils;
import checkers.inference.Reference;
import checkers.inference.Reference.AdaptReference;
import checkers.inference.Reference.ArrayReference;
import checkers.inference.Reference.DeclaredReference;
import checkers.inference.Reference.ExecutableReference;
import checkers.inference.Reference.VoidReference;
import checkers.inference.reim.quals.Mutable;
import checkers.inference.reim.quals.Polyread;
import checkers.inference.reim.quals.Readonly;
import checkers.inference.sflow.quals.Bottom;
import checkers.inference.sflow.quals.Poly;
import checkers.inference.sflow.quals.Tainted;
import checkers.inference.sflow.quals.Safe;
import checkers.inference.sflow.quals.Top;
import checkers.inference.sflow.quals.Uncheck;
import checkers.quals.TypeQualifiers;
import checkers.types.AnnotatedTypeMirror;
import checkers.types.AnnotatedTypeMirror.AnnotatedArrayType;
import checkers.types.AnnotatedTypeMirror.AnnotatedDeclaredType;
import checkers.util.AnnotationUtils;
import checkers.util.ElementUtils;
import checkers.util.InternalUtils;
import checkers.util.MultiGraphQualifierHierarchy.MultiGraphFactory;
import checkers.util.TypesUtils;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.Tree.Kind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Type.ForAll;
import com.sun.tools.javac.code.Type.MethodType;
import com.sun.tools.javac.code.Type.TypeVar;
import com.sun.tools.javac.code.Type.WildcardType;

/**
 * @author huangw5
 * 
 */
@SupportedOptions({ "warn", "checking", "insertAnnos", "debug", "noReim", "inferLibrary", "polyLibrary", "sourceSinkOnly", "inferAndroidApp" })
@TypeQualifiers({ Uncheck.class, Readonly.class, Polyread.class, Mutable.class,
		Poly.class, Tainted.class, Safe.class, Bottom.class, Top.class })
public class SFlowChecker extends InferenceChecker {

	public static AnnotationMirror UNCHECK, READONLY, POLYREAD, MUTABLE, POLY,
			TAINTED, SAFE, BOTTOM, TOP;

	private Map<String, Integer> annoWeights = new HashMap<String, Integer>();

	protected AnnotationUtils annoFactory;

	private Set<String> extraPrimitiveTypes;

	private Set<AnnotationMirror> sourceAnnos;

	private List<Pattern> specialMethodPatterns = null;
	
	private boolean useReim = true;
	
	private boolean polyLibrary = true;
	
	private boolean inferLibrary = false;
	
	private boolean sourceSinkOnly = false;

    private boolean inferAndroidApp = false;
	
	@Override
	public void initChecker(ProcessingEnvironment processingEnv) {
		super.initChecker(processingEnv);
		annoFactory = AnnotationUtils.getInstance(env);
		POLY = annoFactory.fromClass(Poly.class);
		TAINTED = annoFactory.fromClass(Tainted.class);
		SAFE = annoFactory.fromClass(Safe.class);
		BOTTOM = annoFactory.fromClass(Bottom.class);
		TOP = annoFactory.fromClass(Top.class);

		UNCHECK = annoFactory.fromClass(Uncheck.class);

		READONLY = annoFactory.fromClass(Readonly.class);
		POLYREAD = annoFactory.fromClass(Polyread.class);
		MUTABLE = annoFactory.fromClass(Mutable.class);

		annoWeights.put(TAINTED.toString(), 1);
		annoWeights.put(POLY.toString(), 3);
		annoWeights.put(SAFE.toString(), 5);

		sourceAnnos = new HashSet<AnnotationMirror>(4);
		sourceAnnos.add(TAINTED);
		sourceAnnos.add(POLY);
		sourceAnnos.add(SAFE);
		sourceAnnos.add(BOTTOM);
//        sourceAnnos.add(TOP);

		extraPrimitiveTypes = new HashSet<String>();
		extraPrimitiveTypes.add("java.lang.String");

		specialMethodPatterns = new ArrayList<Pattern>(5);
		specialMethodPatterns.add(Pattern
				.compile(".*\\.equals\\(java\\.lang\\.Object\\)$"));
		specialMethodPatterns.add(Pattern.compile(".*\\.hashCode\\(\\)$"));
		specialMethodPatterns.add(Pattern.compile(".*\\.toString\\(\\)$"));
		specialMethodPatterns.add(Pattern.compile(".*\\.compareTo\\(.*\\)$"));
		
		if (getProcessingEnvironment().getOptions().containsKey(
				"inferLibrary")) {
			inferLibrary = true;
		}
		if (getProcessingEnvironment().getOptions().containsKey(
				"polyLibrary")) {
			polyLibrary = true;
		}
		if (getProcessingEnvironment().getOptions().containsKey(
				"sourceSinkOnly")) {
			sourceSinkOnly = true;
		}
		if (getProcessingEnvironment().getOptions().containsKey(
				"noReim")) {
			useReim = false;
		}

		if (getProcessingEnvironment().getOptions().containsKey(
				"inferAndroidApp")) {
            inferAndroidApp = true;
		}

//        if (inferLibrary && polyLibrary) {
//            throw new RuntimeException("inferLibrary and polyLibrary cannot be true at the same time.");
//        }
        
        if (InferenceChecker.DEBUG) {
            System.out.println("INFO: useReim = " + useReim);
            System.out.println("INFO: polyLibrary = " + polyLibrary);
            System.out.println("INFO: inferLibrary = " + inferLibrary);
            System.out.println("INFO: sourceSinkOnly = " + sourceSinkOnly);
            System.out.println("INFO: inferAndroidApp = " + inferAndroidApp);
        }
	}
	
	

	public boolean isUseReim() {
		return useReim;
	}


	public boolean isInferLibrary() {
		return inferLibrary;
	}

	public boolean isPolyLibrary() {
		return polyLibrary;
	}


	public boolean isSourceSinkOnly() {
		return sourceSinkOnly;
	}

    public boolean isInferAndroidApp() {
        return inferAndroidApp; 
    }


	@Override
	protected MultiGraphFactory createQualifierHierarchyFactory() {
		return new SFlowGraphQualifierHierarchy.InferenceGraphFactory(this);
	}


    public boolean isSpecialAndroidClass(String classStr) {
        if (classStr.equals("android.app.Activity")
                || classStr.equals("android.app.Service")
                || classStr.equals("android.location.LocationListener"))
            return true;
        return false;
    }

    @Deprecated
    public boolean isSpecialAndroidMethod(ExecutableElement methodElt) {
		if (methodElt instanceof MethodSymbol) {
            String ownerStr = ((MethodSymbol) methodElt).owner.toString();
            if (isSpecialAndroidClass(ownerStr)
                && !methodElt.getModifiers().contains(Modifier.PRIVATE))
                return true;
        }
        return false;
    }

	public boolean isSpecialMethod(ExecutableElement methodElt) {
		String key = InferenceUtils.getMethodSignature(methodElt);
		for (Pattern p : specialMethodPatterns) {
			if (p.matcher(key).matches()) {
				return true;
			}
		}
		if (methodElt instanceof MethodSymbol) {
			if (((MethodSymbol) methodElt).owner.toString().equals(
					"java.lang.Object"))
				return true;
			else if (((MethodSymbol) methodElt).owner.toString().equals(
					"java.util.Enumeration"))
				return true;
			else if (methodElt.toString().equals("close()")
					&& (((MethodSymbol) methodElt).owner.toString().equals(
							"java.io.Closeable") || ((MethodSymbol) methodElt).owner
							.toString().equals("java.lang.AutoCloseable")))
				return true;
			else if (methodElt.toString().contains("toArray")
					&& (((MethodSymbol) methodElt).owner.toString()
							.equals("java.util.AbstractCollection")))
				return true;
			else if ((methodElt.toString().contains("compare") || methodElt
					.toString().contains("equals"))
					&& (((MethodSymbol) methodElt).owner.toString()
							.equals("java.util.Comparator")))
				return true;
			else if ((((MethodSymbol) methodElt).owner.toString()
					.equals("java.io.InputStream"))
					&& (methodElt.toString().contains("read")))
				return true;
		}
		return false;
	}

	/**
	 * Our readonly type also includes String, Integer, and more
	 * 
	 * @param type
	 * @return
	 */
	public boolean isReadonlyType(AnnotatedTypeMirror type) {
		TypeElement elt = null;
		if (type.getKind() == TypeKind.DECLARED) {
			AnnotatedDeclaredType dt = (AnnotatedDeclaredType) type;
			elt = (TypeElement) dt.getUnderlyingType().asElement();
			String qualifiedName = elt.getQualifiedName().toString();
			if (extraPrimitiveTypes.contains(qualifiedName))
				return true;
		}
		return type.getKind().isPrimitive()
				|| TypesUtils.isBoxedPrimitive(type.getUnderlyingType());
	}
	
	public boolean isTaintableType(AnnotatedTypeMirror type) {
		
		if (type.getKind() == TypeKind.ARRAY) 
			return isTaintableType(((AnnotatedArrayType) type).getComponentType());
		
		return isReadonlyType(type)
				|| type.getUnderlyingType().toString().startsWith("java.util.ArrayList")
				|| type.getUnderlyingType().toString().startsWith("java.util.List")
				|| type.getUnderlyingType().toString().startsWith("java.util.StringTokenizer")
				|| type.getUnderlyingType().toString().startsWith("java.util.Properties")
				|| type.getUnderlyingType().toString().startsWith("java.util.Iterator<")
				|| type.getUnderlyingType().toString().equals("java.lang.StringBuffer")
				|| type.getUnderlyingType().toString().equals("java.lang.StringBuilder");
	}
	
	
	public boolean isTaintableRef(Reference ref) {
        // FIXME:
        return true;
//        AnnotatedTypeMirror type = ref.getType();
//        Element elt = ref.getElement();
//        Tree tree = ref.getTree();
//        if (type != null && isTaintableType(type))
//            return true;
//        else if (elt != null && elt.getKind() == ElementKind.LOCAL_VARIABLE)
//            return true;
//        else if (elt == null && (elt = InternalUtils.symbol(ref.getTree())) != null 
//                && elt.getKind() == ElementKind.LOCAL_VARIABLE)
//            return true;
//        else if (tree != null 
//                && (tree.getKind() == Kind.NEW_CLASS || tree.getKind() == Kind.NEW_ARRAY))
//            return true;
//        return false;
	}
	

	@Override
	public boolean isAnnotated(AnnotatedTypeMirror type) {
		if (!type.isAnnotated())
			return false;
		if (InferenceUtils.intersectAnnotations(sourceAnnos,
				type.getAnnotations()).isEmpty())
			return false;
		return true;
	}


    @Override
	public boolean isAnnotated(Reference ref) {
		if (ref.getAnnotations().isEmpty())
            return false;
		if (InferenceUtils.intersectAnnotations(sourceAnnos,
				ref.getAnnotations()).isEmpty())
			return false;
		return true;
	}


    public boolean isParamReturnConstraint(Constraint c) {
        Reference left = c.getLeft();
        Reference right = c.getRight();
        if (isParamOrRetRef(left) && isParamOrRetRef(right)) {
            Element lElt = null;
            Element rElt = null;
            // check if they are from the same method
            lElt = getEnclosingMethod(left.getElement());
            rElt = getEnclosingMethod(right.getElement());
            if (lElt.equals(rElt))
                return true;
        }
        return false;
    }

    public boolean isParamOrRetRef(Reference ref) {
        if (ref != null && !(ref instanceof AdaptReference)) {
            Element elt = null;
            if ((elt = ref.getElement()) != null 
                    && (elt.getKind() == ElementKind.PARAMETER 
                        || ref.getRefName().startsWith("RET_")
                        || ref.getRefName().startsWith("THIS_")))
                return true;
        }
        return false;
    }

    public Element getEnclosingMethod(Element elt) {
        while (elt != null && elt.getKind() != ElementKind.METHOD
                && elt.getKind() != ElementKind.CONSTRUCTOR) {
            elt = elt.getEnclosingElement();
        }
        return elt;
    }

	/**
	 * We need to override this method because we need to distinguish primitive
	 * and reference
	 */
	@Override
	public void fillAllPossibleAnnos(List<Reference> refs) {
		Set<AnnotationMirror> sflowSet = AnnotationUtils.createAnnotationSet();
		sflowSet.add(TAINTED);
		sflowSet.add(POLY);
		sflowSet.add(SAFE);
		
		Set<AnnotationMirror> reimSet = AnnotationUtils.createAnnotationSet();
		reimSet.add(READONLY);
		reimSet.add(POLYREAD);
		reimSet.add(MUTABLE);
		
		for (Reference ref : refs) {
            if (ref instanceof ExecutableReference)
                continue;
//            Set<AnnotationMirror> annos = ref.getAnnotations();
			// remove reim qualifiers
//            annos = InferenceUtils.differAnnotations(annos, reimSet);
//            if (InferenceUtils.intersectAnnotations(sflowSet, annos).isEmpty()) {
            if (!isAnnotated(ref)) {
				// WEI: move from annotateMethod on Mar 30, 2013
                Set<AnnotationMirror> annos = AnnotationUtils.createAnnotationSet();
				Element elt = ref.getElement();
				if (elt != null && isFromLibrary(elt) 
                        && isPolyLibrary()
                        && (elt.getKind() == ElementKind.METHOD 
                            || elt.getKind() == ElementKind.CONSTRUCTOR 
                            || elt.getKind() == ElementKind.PARAMETER)) {
					annos.add(POLY);
				}
				else
					annos.addAll(sflowSet);
				ref.setAnnotations(annos);
			}
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see checkers.inference.InferenceChecker#isStrictSubtyping()
	 */
	@Override
	public boolean isStrictSubtyping() {
		return true;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * checkers.inference.InferenceChecker#getFailureStatus(checkers.inference
	 * .Constraint)
	 */
	@Override
	public FailureStatus getFailureStatus(Constraint c) {
		// Reference left = c.getLeft();
		// Reference right = c.getRight();
		// AnnotatedTypeMirror leftType = left.getType();
		// AnnotatedTypeMirror rightType = right.getType();
		// if (leftType != null && isReadonlyType(leftType)
		// || rightType != null && isReadonlyType(rightType))
		// return FailureStatus.IGNORE;
		return FailureStatus.ERROR;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * checkers.inference.InferenceChecker#getInferenceVisitor(checkers.inference
	 * .InferenceChecker, com.sun.source.tree.CompilationUnitTree)
	 */
	@Override
	public BaseTypeVisitor<?> getInferenceVisitor(InferenceChecker checker,
			CompilationUnitTree root) {
		return new SFlowInferenceVisitor((SFlowChecker) checker, root);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see checkers.inference.InferenceChecker#getSourceLevelQualifiers()
	 */
	@Override
	public Set<AnnotationMirror> getSourceLevelQualifiers() {
		return sourceAnnos;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * checkers.inference.InferenceChecker#adaptField(javax.lang.model.element
	 * .AnnotationMirror, javax.lang.model.element.AnnotationMirror)
	 */
	@Override
	public AnnotationMirror adaptField(AnnotationMirror contextAnno,
			AnnotationMirror declAnno) {
		String contextStr = contextAnno.toString();
		String declStr = declAnno.toString();
		if (contextStr.equals(BOTTOM.toString()))
			return null;
		// return declAnno;
		if (annoWeights.get(contextStr) == null
				|| annoWeights.get(declStr) == null) {
			if (!isChecking())
				return null;
			else {
				// Keep readonly
				if (contextStr.equals(READONLY.toString())
						|| declStr.equals(READONLY.toString()))
					return READONLY;
				else
					return null;
			}
		}
		if (declStr.equals(POLY.toString()))
			return contextAnno;
		else if (declStr.equals(SAFE.toString()))
			return SAFE;
		else if (declStr.equals(TAINTED.toString()))
			return TAINTED;
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * checkers.inference.InferenceChecker#adaptMethod(javax.lang.model.element
	 * .AnnotationMirror, javax.lang.model.element.AnnotationMirror)
	 */
	@Override
	public AnnotationMirror adaptMethod(AnnotationMirror contextAnno,
			AnnotationMirror declAnno) {
		return adaptField(contextAnno, declAnno);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * checkers.inference.InferenceChecker#getAnnotaionWeight(javax.lang.model
	 * .element.AnnotationMirror)
	 */
	@Override
	public int getAnnotaionWeight(AnnotationMirror anno) {
		Integer weight = annoWeights.get(anno.toString());
		if (weight == null)
			return Integer.MAX_VALUE;
		else
			return weight;
	}
	
	

	@Override
	public Reference getMaximal(Reference ref) {
		if (!isSourceSinkOnly())
			return super.getMaximal(ref);
		else {
			Reference copy = ref;
			Set<AnnotationMirror> annos = copy.getAnnotations();
			if (annos.size() == 0)
				return copy;
			else if (annos.size() > 1) {
				annos.clear();
				annos.add(POLY);
			} 
			// get the maximal annotation
			copy.setAnnotations(annos);
			
			// Check the type of ref
			if (copy instanceof DeclaredReference) {
				List<? extends Reference> typeArgs = 
						((DeclaredReference) copy).getTypeArguments();
				if (typeArgs != null) {
					List<Reference> ts = new ArrayList<Reference>(typeArgs.size());
					for (Reference typeArgRef : typeArgs)
						ts.add(getMaximal(typeArgRef));
					((DeclaredReference) copy).setTypeArguments(ts);
				}
			} else if (copy instanceof ArrayReference) {
				Reference componentRef = ((ArrayReference) copy).getComponentRef();
				((ArrayReference) copy).setComponentRef(getMaximal(componentRef));
			} else if (copy instanceof ExecutableReference) {
				ExecutableReference executableRef = (ExecutableReference) copy;
				executableRef.setReceiverRef(getMaximal(executableRef.getReceiverRef()));
				executableRef.setReturnRef(getMaximal(executableRef.getReturnRef()));
			} 
			return copy;	
		}
	}



	/*
	 * (non-Javadoc)
	 * 
	 * @see checkers.inference.InferenceChecker#printResult(java.util.Map,
	 * java.io.PrintWriter)
	 */
	@Override
	public void printResult(Map<String, Reference> solution, PrintWriter out) {
		Collection<Reference> values = solution.values();
		List<Reference> lst = new ArrayList<Reference>(values.size());
		for (Reference ref : values) {
			if (ref.getElement() != null
					&& !ref.getFileName().startsWith("zLIB")
					&& ref.getLineNum() > 0) {
				lst.add(ref);
			}
		}
		Collections.sort(lst, new Comparator<Reference>() {
			@Override
			public int compare(Reference o1, Reference o2) {
				int res = o1.getFileName().compareTo(o2.getFileName());
				if (res != 0)
					return res;
				else
					res = (int) (o1.getLineNum() - o2.getLineNum());
				if (res != 0)
					return res;
				else if (o1.getElement() != null && o2.getElement() != null) {
					return o1.getElement().toString()
							.compareTo(o2.getElement().toString());
				}
				return 0;
			}
		});

		int taintedNum = 0, polyNum = 0, safeNum = 0;
		int totalElementNum = 0;
		int localTaintedNum = 0, localPolyNum = 0, localSafeNum = 0;
		
		for (Reference ref : lst) {
//			AnnotatedTypeMirror tmpType = null;
			Element elt = ref.getElement();
			String isStatic = (elt != null ? (ElementUtils.isStatic(elt) ? "STATIC"
					: "INSTANT")
					: "UNKOWN");
			String modifier = (elt != null ? elt.getModifiers().toString() : "DEFAULT");
			if (ref instanceof ExecutableReference) {
				if (isCompilerAddedConstructor((ExecutableElement) ref
						.getElement()))
					continue;
				out.println(ref.getFileName() + "\t" + ref.getLineNum() + "\t"
						+ ref.getElement() + "\t\t" + ref.getAnnotatedType()
						+ "\t" + isStatic + "\t" + "METHOD" + "\t" + modifier);
				
				ExecutableReference eRef = (ExecutableReference) ref;
				// receiver
				if (!ElementUtils.isStatic(ref.getElement())) {
					totalElementNum++;
					AnnotatedTypeMirror rcvType = eRef.getReceiverRef().getAnnotatedType();
					if (rcvType.hasAnnotation(TAINTED))
						taintedNum++;
					else if (rcvType.hasAnnotation(POLY))
						polyNum++;
					else if (rcvType.hasAnnotation(SAFE))
						safeNum++;
					else 
						System.err.println("WARN: Unknown type! " + rcvType);
				}
				// return
				AnnotatedTypeMirror returnType = eRef.getReturnRef().getAnnotatedType();
				if (returnType != null
						&& returnType.getKind() != TypeKind.VOID) {
					totalElementNum++;
					if (returnType.hasAnnotation(TAINTED))
						taintedNum++;
					else if (returnType.hasAnnotation(POLY))
						polyNum++;
					else if (returnType.hasAnnotation(SAFE))
						safeNum++;
					else 
						System.err.println("WARN: Unknown type! " + returnType);
				}
				
				
			} else {
				// output type
				if (ref.getElement() != null) {
					out.println(ref.getFileName() + "\t" + ref.getLineNum()
							+ "\t" + ref.getElement() + "\t\t"
							+ ref.getAnnotatedType() + "\t" + isStatic + "\t"
							+ elt.getKind().toString() + "\t\t" + modifier);
					AnnotatedTypeMirror type = ref.getAnnotatedType();
					if (type.getKind() != TypeKind.VOID) {
						totalElementNum++;
						if (type.hasAnnotation(TAINTED)) {
							taintedNum++;
							if (ref.getElement().getKind() == ElementKind.LOCAL_VARIABLE)
								localTaintedNum++;
						} else if (type.hasAnnotation(POLY)) {
							polyNum++;
							if (ref.getElement().getKind() == ElementKind.LOCAL_VARIABLE)
								localPolyNum++;
						} else if (type.hasAnnotation(SAFE)) {
							safeNum++;
							if (ref.getElement().getKind() == ElementKind.LOCAL_VARIABLE)
								localSafeNum++;
						} else 
							System.err.println("WARN: Unknown type! " + type);
					}
				}
			}
			// statistics
		}
		
		String s = "INFO: There are " + taintedNum + " ("
				+ (((float) taintedNum / totalElementNum) * 100)
				+ "%) tainted, " + polyNum + " ("
				+ (((float) polyNum / totalElementNum) * 100)
				+ "%) poly and " + safeNum + " ("
				+ (((float) safeNum / totalElementNum) * 100) + "%) safe"
				+ " references out of " + totalElementNum + " references";
		
		taintedNum = taintedNum - localTaintedNum;
		polyNum = polyNum - localPolyNum;
		safeNum = safeNum - localSafeNum;
		totalElementNum = totalElementNum - localTaintedNum - localPolyNum - localSafeNum;
		
		String s2 = "INFO: There are " + taintedNum + " ("
				+ (((float) taintedNum / totalElementNum) * 100)
				+ "%) tainted, " + polyNum + " ("
				+ (((float) polyNum / totalElementNum) * 100)
				+ "%) poly and " + safeNum + " ("
				+ (((float) safeNum / totalElementNum) * 100) + "%) safe"
				+ " references out of " + totalElementNum + " references excluding local variables";
		
		System.out.println(s);
		out.println(s);
		System.out.println(s2);
		out.println(s2);
		
		
		// Print the JAIF result
		// if (getProcessingEnvironment().getOptions().containsKey(
		// "jaif")) {
		PrintWriter pw = null;
		try {
			pw = new PrintWriter(InferenceMain.outputDir + File.separator
					+ "result.jaif");
			printJAIFResult(solution, pw);
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		if (pw != null)
			pw.close();
		// }
		if (getProcessingEnvironment().getOptions().containsKey(
				"insertAnnos")) {
			insertInferredAnnotations(lst);
		}
	}

	@Override
	public boolean needCheckConflict() {
		return true;
	}

	// @Override
	public void resolveConflictConstraints(List<Constraint> conflictConstraints) {
		// int num;
		// if ((num = resolveReceiverConflicts(conflictConstraints)) == 0) {
		// System.out.println("INFO: Resolved " + num +
		// " conflicts on receivers");
		// if ((num = resolveFieldConflicts(conflictConstraints)) == 0) {
		// System.out.println("INFO: Resolved " + num + " conflicts on fields");
		// if ((num = resolveReturnConflicts(conflictConstraints)) == 0) {
		// System.out.println("INFO: Resolved " + num +
		// " conflicts on returns");
		// num = resolveParameterConflicts(conflictConstraints);
		// System.out.println("INFO: Resolved " + num +
		// " conflicts on parameters");
		// }
		// }
		// }
		int num;
		num = resolveFieldConflicts(conflictConstraints);
		System.out.println("INFO: Resolved " + num + " conflicts on fields");
		if (num == 0) {
			num = resolveReceiverConflicts(conflictConstraints);
			System.out.println("INFO: Resolved " + num
					+ " conflicts on receivers");
			if (num == 0) {
				num = resolveReturnConflicts(conflictConstraints);
				System.out.println("INFO: Resolved " + num
						+ " conflicts on returns");
				if (num == 0) {
					num = resolveParameterConflicts(conflictConstraints);
					System.out.println("INFO: Resolved " + num
							+ " conflicts on parameters");
					if (num == 0) {
						num = resolveArrayLenConflicts(conflictConstraints);
						System.out.println("INFO: Resolved " + num
								+ " conflicts on Array.length");
					}
				}
			}
		}
	}

	// @Override
	public void resolveConflictConstraints2(List<Constraint> conflictConstraints) {
		// System.out.println("INFO: There are " + conflictConstraints.size() +
		// " conflicts");
		for (Constraint c : conflictConstraints) {
			// if (!(c instanceof SubtypeConstraint)) {
			// continue;
			// }
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();
			// if (declElt != null && declElt.getKind() == ElementKind.FIELD
			// && declRef.getAnnotations().size() > 1
			// && declRef.getAnnotations().contains(SAFE)) {
			// Set<AnnotationMirror> annos = declRef.getAnnotations();
			// annos.remove(SAFE);
			// declRef.setAnnotations(annos);
			// }

			// Conflict due to Safe RET
			// Element declElt = declRef.getElement();
			// if (declElt != null && declElt.getKind() == ElementKind.METHOD
			// && declRef.getReadableName() != null
			// && declRef.getReadableName().startsWith("RET_")
			// && declRef.getAnnotations().size() > 1
			// && declRef.getAnnotations().contains(SAFE)) {
			// Set<AnnotationMirror> annos = declRef.getAnnotations();
			// annos.remove(SAFE);
			// declRef.setAnnotations(annos);
			// }

			if (contextRef.getAnnotations().size() > 1
					&& (contextElt == null || contextElt.getKind() != ElementKind.FIELD)
					&& contextRef.getAnnotations().contains(TAINTED)) {
				Set<AnnotationMirror> annos = contextRef.getAnnotations();
				annos.remove(TAINTED);
				contextRef.setAnnotations(annos);
			} else if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& declElt != null
					&& declElt.getKind() == ElementKind.FIELD
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as SAFE
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				// annos.add(POLY);
				annos.add(SAFE);
				declRef.setAnnotations(annos);
			} else if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& declElt != null
					&& declElt.getKind() == ElementKind.METHOD
					&& declRef.getRefName().startsWith("RET_")
					&& declRef.getAnnotations().size() > 1
					// && declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as SAFE
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				annos.add(SAFE);
				// annos.add(POLY);
				declRef.setAnnotations(annos);
			} else if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& contextRef.getAnnotations().contains(SAFE)
					&& contextRef.getAnnotations().contains(POLY)
					&& (declElt == null || declElt.getKind() == ElementKind.PARAMETER)
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as POLY
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				annos.add(SAFE);
				declRef.setAnnotations(annos);
			}
		}
	}

	private int resolveFieldConflicts(List<Constraint> conflictConstraints) {
		int num = 0;
		for (Constraint c : conflictConstraints) {
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();

			if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& declElt != null
					&& declElt.getKind() == ElementKind.FIELD
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as SAFE
				if (declElt != null && declElt.toString().equals("length")
						&& contextRef instanceof ArrayReference) {
					// This is array.length
					continue;
				}
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				annos.add(POLY);
				// annos.add(SAFE);
				declRef.setAnnotations(annos);
				num++;
				// System.out.println("Resolved: " + c.toString());
				// if (num == 10)
				// break;
			}
		}
		return num;
	}

	private int resolveArrayLenConflicts(List<Constraint> conflictConstraints) {
		int num = 0;
		for (Constraint c : conflictConstraints) {
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();

			if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& declElt != null
					&& declElt.getKind() == ElementKind.FIELD
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as SAFE
				if (declElt != null && declElt.toString().equals("length")
						&& contextRef instanceof ArrayReference) {
					// This is array.length
					Set<AnnotationMirror> annos = declRef.getAnnotations();
					annos.clear();
					annos.add(POLY);
					// annos.add(SAFE);
					declRef.setAnnotations(annos);
					num++;
				}
			}
		}
		return num;
	}

	private int resolveParameterConflicts(List<Constraint> conflictConstraints) {
		int num = 0;
		for (Constraint c : conflictConstraints) {
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();

			if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& contextRef.getAnnotations().contains(SAFE)
					&& contextRef.getAnnotations().contains(POLY)
					&& (declElt == null || declElt.getKind() == ElementKind.PARAMETER)
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				// set declRef as POLY
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				if (declElt == null
						|| !ElementUtils
								.isStatic(declElt.getEnclosingElement())) {
					// Nonstatic
					annos.add(SAFE);
				} else
					annos.add(POLY);
				declRef.setAnnotations(annos);
				num++;
			}
		}
		return num;
	}

	private int resolveReturnConflicts(List<Constraint> conflictConstraints) {
		int num = 0;
		for (Constraint c : conflictConstraints) {
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();

			if (outRef.getAnnotations().size() == 1
					&& outRef.getAnnotations().contains(SAFE)
					&& declElt != null
					&& declElt.getKind() == ElementKind.METHOD
					&& declRef.getRefName().startsWith("RET_")
					&& declRef.getAnnotations().size() > 1
					&& declRef.getAnnotations().contains(POLY)
					&& declRef.getAnnotations().contains(SAFE)) {
				Set<AnnotationMirror> annos = declRef.getAnnotations();
				annos.clear();
				String methodSig = InferenceUtils.getMethodSignature(declElt);
				boolean isSpecial = false;
				for (Pattern p : specialMethodPatterns) {
					if (p.matcher(methodSig).matches()) {
						isSpecial = true;
						break;
					}
				}
				if (!isSpecial) {
					// set declRef as SAFE
					annos.add(SAFE);
				} else {
					annos.add(POLY);
				}
				declRef.setAnnotations(annos);
				num++;
			}
		}
		return num;
	}

	private int resolveReceiverConflicts(List<Constraint> conflictConstraints) {
		int num = 0;
		for (Constraint c : conflictConstraints) {
			Reference left = c.getLeft();
			Reference right = c.getRight();
			Reference declRef, contextRef, outRef;
			if (left instanceof AdaptReference) {
				outRef = right;
				declRef = ((AdaptReference) left).getDeclRef();
				contextRef = ((AdaptReference) left).getContextRef();
			} else if (right instanceof AdaptReference) {
				outRef = left;
				declRef = ((AdaptReference) right).getDeclRef();
				contextRef = ((AdaptReference) right).getContextRef();
			} else {
				// No adapt constraint
				continue;
			}
			Element contextElt = contextRef.getElement();
			Element declElt = declRef.getElement();

			if (contextRef.getAnnotations().size() > 1
					&& (contextElt == null || contextElt.getKind() != ElementKind.FIELD)
					&& contextRef.getAnnotations().contains(TAINTED)) {
				Set<AnnotationMirror> annos = contextRef.getAnnotations();
				annos.remove(TAINTED);
				contextRef.setAnnotations(annos);
				num++;
			}
			// else
			// if (outRef.getAnnotations().size() == 1
			// && outRef.getAnnotations().contains(SAFE)
			// && declElt != null && declElt.getKind() == ElementKind.FIELD
			// && declRef.getAnnotations().size() > 1
			// && declRef.getAnnotations().contains(POLY)
			// && declRef.getAnnotations().contains(SAFE)) {
			// // set declRef as SAFE
			// Set<AnnotationMirror> annos = declRef.getAnnotations();
			// annos.clear();
			// annos.add(POLY);
			// // annos.add(SAFE);
			// declRef.setAnnotations(annos);
			// num++;
			// }
		}
		return num;
	}

	private void printJAIFResult(Map<String, Reference> solution,
			PrintWriter out) {
		TypeElement[] visitedClasses = InferenceMain.getInstance()
				.getConstraintManager().getVisitedClasses();
		// First sort the visitedClasses
		Arrays.sort(visitedClasses, new Comparator<TypeElement>() {
			@Override
			public int compare(TypeElement o1, TypeElement o2) {
				Element elt1 = o1.getEnclosingElement();
				while (elt1.getKind() != ElementKind.PACKAGE) {
					elt1 = elt1.getEnclosingElement();
				}
				PackageElement packageElt1 = (PackageElement) elt1;
				String packageName1 = packageElt1.getQualifiedName().toString();
				packageName1 = packageElt1.isUnnamed() ? "" : packageName1;

				Element elt2 = o2.getEnclosingElement();
				while (elt2.getKind() != ElementKind.PACKAGE) {
					elt2 = elt2.getEnclosingElement();
				}
				PackageElement packageElt2 = (PackageElement) elt2;
				String packageName2 = packageElt2.getQualifiedName().toString();
				packageName2 = packageElt2.isUnnamed() ? "" : packageName2;

				int res = packageName1.compareTo(packageName2);
				if (res != 0)
					return res;

				String className1 = ((ClassSymbol) o1).flatName().toString();
				if (!packageElt1.isUnnamed()) {
					className1 = className1.substring(packageName1.length() + 1);
				}

				String className2 = ((ClassSymbol) o2).flatName().toString();
				if (!packageElt2.isUnnamed()) {
					className2 = className2.substring(packageName2.length() + 1);
				}
				res = className1.compareTo(className2);
				return res;
			}
		});
		
		// Make a JAIF dir
		String dirName = InferenceMain.outputDir + File.separator + "JAIF";
		File dir = new File(dirName);
		if (dir.exists()) {
			deleteDirectory(dir);
		}
		dir.mkdir();

		// Write annotation declarations
		printAnnoDeclaration(out);

		// Now write the JAIF result
		for (TypeElement clazz : visitedClasses) {
			// Output package name
			Element pElt = clazz;
			while (pElt.getKind() != ElementKind.PACKAGE) {
				pElt = pElt.getEnclosingElement();
			}
			PackageElement packageElt = (PackageElement) pElt;
			String packageName = packageElt.isUnnamed() ? "" 
					: packageElt.getQualifiedName().toString();
			String className = ((ClassSymbol) clazz).flatName().toString();
			String fileName = (packageName.equals("") ? className : packageName + "." + className) + ".jaif";
			
//			PrintWriter out;
//			try {
//				out = new PrintWriter(dirName + File.separator + fileName);
				
//		printAnnoDeclaration(out);
			
			out.println("package "
					+ (packageName) + ":");

			// Output class name
			if (!packageElt.isUnnamed()) {
				className = className.substring(packageName.length() + 1);
			}
			out.println("class " + className + ":");
			
			boolean isInterface = clazz.getKind().isInterface();

			// Output members
			List<? extends Element> enclosedElements = clazz
					.getEnclosedElements();
			// first output fields
			for (Element elt : enclosedElements) {
				if (elt.getKind() == ElementKind.FIELD) {
					out.println("\n    field " + elt.toString() + ":");
					Reference ref = solution.get(InferenceUtils
							.getElementSignature(elt));
					if (ref != null) {
						printTypeAnnotation(out, ref, "type", "        ");
//						out.print("        type: ");
//						out.println(extractAnnotations(ref));
//						int i = 0;
//						String prefix = "";
//						while (ref instanceof ArrayReference) {
//							ref = ((ArrayReference) ref).getComponentRef();
//							out.print(prefix + "            inner-type " + i
//									+ ":");
//							if (ref == null) {
//								out.println();
//								break;
//							}
//							out.println(" " + extractAnnotations(ref));
//							i++;
//							prefix += "    ";
//						}
					}
				}
			}
			// constructors and methods
			for (Element elt : enclosedElements) {
				if (elt.getKind() == ElementKind.CONSTRUCTOR
						|| elt.getKind() == ElementKind.METHOD) {
					ExecutableElement methodElt = (ExecutableElement) elt;
					ExecutableReference methodRef = (ExecutableReference) solution
							.get(InferenceUtils.getElementSignature(methodElt));
					if (methodRef == null) {
						continue;
					}
					boolean isAbstract = methodElt.getModifiers().contains(Modifier.ABSTRACT);
//					if (methodElt.toString().contains("setValue("))
//						System.out.println();
					Type t = ((MethodSymbol) methodElt).type;
					if (t instanceof ForAll) {
						t = t.asMethodType();
					}
					if (t instanceof MethodType) {
						MethodType mt = (MethodType) t;
						StringBuffer sb = new StringBuffer();
						for (Type paramType : mt.getParameterTypes()) {
							if (paramType.getKind() == TypeKind.TYPEVAR) {
								sb.append(((TypeVar) paramType).getUpperBound().toString());
							} else if (paramType.getKind() == TypeKind.WILDCARD) {
								sb.append(((WildcardType) paramType)
										.getExtendsBound() == null ? "java.lang.Object"
										: ((WildcardType) paramType)
												.getExtendsBound().toString());
								System.out.println("Wildcard in " + className + ":" + methodElt.toString());
							} else
								sb.append(paramType.toString());
							sb.append(",");
						}
						if (sb.length() > 0)
							sb.deleteCharAt(sb.length() - 1);
						String javadocSig = "(" + sb.toString() + ")";
						String javadocReturnType = mt.restype.toString();
						if (mt.restype.getKind() == TypeKind.TYPEVAR) {
							javadocReturnType = ((TypeVar) mt.restype).getUpperBound().toString();
						} else if (mt.restype.getKind() == TypeKind.WILDCARD) {
							javadocReturnType = ((WildcardType) mt.restype)
									.getExtendsBound() == null ? "java.lang.Object"
									: ((WildcardType) mt.restype)
											.getExtendsBound().toString();
						}
						javadocSig = javadocSig.replaceAll(
								"<[a-zA-Z_0-9?,; ]+>", "");
						javadocReturnType = javadocReturnType.replaceAll(
								"<[a-zA-Z_0-9?,; ]+>", "");
						String jvmSig = DescriptorUtil.convert(javadocSig,
								javadocReturnType);
						out.println("\n    method "
								+ ((MethodSymbol) methodElt).name + jvmSig
								+ ":");
					} else {
						out.println("\n    method " + methodElt + ":");
					}
					Reference returnRef = methodRef.getReturnRef();
					printTypeAnnotation(out, returnRef, "return", "        ");
					if (!ElementUtils.isStatic(methodElt)) {
						Reference rcvRef = methodRef.getReceiverRef();
						printTypeAnnotation(out, rcvRef, "receiver", "        ", isInterface || isAbstract);
					}

					List<? extends VariableElement> parameters = methodElt
							.getParameters();
					int counter = 0;
					for (VariableElement e : parameters) {
						try {
							out.println("        parameter "
									+ /* e.toString() */"#" + (counter++) + ":");
							Reference paramRef = solution.get(InferenceUtils
									.getElementSignature(e));
							printTypeAnnotation(out, paramRef, "type", "            ", isInterface || isAbstract);
						} catch (Exception ex) {
						}
					}
				}
			}
			out.println();
//			out.close();
//			} catch (FileNotFoundException e1) {
//				// TODO Auto-generated catch block
//				e1.printStackTrace();
//			}
		}

	}
	
	private void printAnnoDeclaration(PrintWriter out) {
		out.println("package checkers.inference.reim.quals:");
		String[] strs = { "@Mutable", "@Polyread", "@Readonly" };
		for (String s : strs) {
			out.println("annotation "
					+ s
					+ ": @java.lang.annotation.Target(value={TYPE_USE,TYPE_PARAMETER})");
		}
		out.println();
		out.println("package checkers.inference.sflow.quals:");
		strs = new String[] { "@Tainted", "@Poly", "@Safe" };
		for (String s : strs) {
			out.println("annotation "
					+ s
					+ ": @java.lang.annotation.Target(value={TYPE_USE,TYPE_PARAMETER})");
		}
		out.println();
	}
	
	
	private void printTypeAnnotation(PrintWriter out, Reference ref,
			String type, String prefix) {
		printTypeAnnotation(out, ref, type, prefix, false);
	}
	
	private void printTypeAnnotation(PrintWriter out, Reference ref,
			String type, String prefix, boolean isAbstract) {
		out.print(prefix + type + ": ");
		if (ref instanceof VoidReference) {
			out.println();
			return;
		}
		out.println(extractAnnotations(ref, isAbstract));
		int i = 0;
		while (ref instanceof ArrayReference) {
			ref = ((ArrayReference) ref).getComponentRef();
			prefix += "    ";
			out.print(prefix + "inner-type " + i + ":");
			if (ref == null) {
				out.println();
				break;
			}
			out.println(" " + extractAnnotations(ref));
			i++;
			prefix += "    ";
		}
	}
	

	private String extractAnnotations(Reference ref) {
		return extractAnnotations(ref, false);
	}
	
	/**
	 * 
	 * @param ref
	 * @param isAbstract: If it is true and ref contains the maximal qual, we 
	 * add other quals
	 * @return
	 */
	private String extractAnnotations(Reference ref, boolean isAbstract) {
		StringBuffer sb = new StringBuffer();
		Set<AnnotationMirror> annotations = ref.getAnnotations();
		for (AnnotationMirror anno : annotations) {
			sb.append(anno.toString() + " ");
			if (isAbstract) {
				if (anno.toString().equals(READONLY.toString())) {
					sb.append(POLYREAD.toString() + " ");
					sb.append(MUTABLE.toString() + " ");
				} else if (anno.toString().equals(TAINTED.toString())) {
					sb.append(POLY.toString() + " ");
					sb.append(SAFE.toString() + " ");
				} 
			}
		}
		return sb.toString();
	}
	
	public static boolean deleteDirectory(File directory) {
	    if(directory.exists()){
	        File[] files = directory.listFiles();
	        if(null!=files){
	            for(int i=0; i<files.length; i++) {
	                if(files[i].isDirectory()) {
	                    deleteDirectory(files[i]);
	                }
	                else {
	                    files[i].delete();
	                }
	            }
	        }
	    }
	    return(directory.delete());
	}

}
