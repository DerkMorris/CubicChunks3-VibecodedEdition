import com.google.gson.GsonBuilder;
import com.sun.source.tree.AnnotationTree;
import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.LiteralTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewArrayTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Parse Java source trees without compiling the unfinished port, and compare
 * DASM member references to the exact vanilla class files. This checks source
 * descriptors, not the correctness of the rewritten destination method body.
 */
public final class AuditDasm {
    private final Map<String, JarFile> classJars = new HashMap<>();
    private final Map<String, ClassNode> classCache = new HashMap<>();
    private final List<Map<String, Object>> missing = new ArrayList<>();
    private final List<Map<String, Object>> skipped = new ArrayList<>();
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private final Map<String, String> containerOwners = new HashMap<>();
    private Path sourceRoot;

    public static void main(String[] args) throws Exception {
        if (args.length < 3) throw new IllegalArgumentException("AuditDasm <source root> <output.json> <minecraft.jar>...");
        new AuditDasm().run(args);
    }

    private void run(String[] args) throws Exception {
        sourceRoot = Path.of(args[0]).toAbsolutePath();
        for (int i = 2; i < args.length; i++) {
            JarFile jar = new JarFile(args[i]);
            jar.stream().filter(e -> e.getName().endsWith(".class")).forEach(e ->
                classJars.put(e.getName().substring(0, e.getName().length() - 6), jar));
        }
        List<Path> sources;
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            sources = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("movetoforgesourcesetlater"))
                .filter(p -> !p.getFileName().toString().equals("ForgeSet.java")).toList();
        }
        var compiler = ToolProvider.getSystemJavaCompiler();
        try (var manager = compiler.getStandardFileManager(null, null, null)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, null, List.of("-proc:none"), null,
                manager.getJavaFileObjectsFromPaths(sources));
            Trees trees = Trees.instance(task);
            List<CompilationUnitTree> units = new ArrayList<>();
            task.parse().forEach(units::add);
            for (CompilationUnitTree unit : units) new Scanner(unit, trees, true).scan(unit, null);
            for (CompilationUnitTree unit : units) new Scanner(unit, trees, false).scan(unit, null);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("checked", counts);
        result.put("missing", missing);
        result.put("skippedNonVanillaOrUnresolved", skipped);
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(result);
        Files.writeString(Path.of(args[1]), json);
        System.out.println("Checked " + counts + "; missing=" + missing.size() + "; skipped=" + skipped.size());
        for (var error : missing) System.out.println(error);
        for (JarFile jar : Set.copyOf(classJars.values())) jar.close();
        if (!missing.isEmpty()) System.exit(1);
    }

    private ClassNode node(String name) {
        if (!classJars.containsKey(name)) return null;
        return classCache.computeIfAbsent(name, key -> {
            try (var stream = classJars.get(key).getInputStream(classJars.get(key).getJarEntry(key + ".class"))) {
                ClassNode node = new ClassNode();
                new ClassReader(stream).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                return node;
            } catch (IOException exception) { throw new IllegalStateException(exception); }
        });
    }

    private boolean exists(String owner, String member, boolean field, boolean inherit) {
        ClassNode n = node(owner);
        if (n == null) return false;
        // DASM also accepts name-only selectors, e.g. "<clinit>".
        boolean direct = field ? n.fields.stream().anyMatch(f -> (f.name + ":" + f.desc).equals(member))
            : n.methods.stream().anyMatch(m -> (member.contains("(") ? m.name + m.desc : m.name).equals(member));
        if (direct || !inherit || member.startsWith("<init>")) return direct;
        if (n.superName != null && exists(n.superName, member, field, true)) return true;
        return n.interfaces.stream().anyMatch(i -> exists(i, member, field, true));
    }

    private final class Scanner extends TreePathScanner<Void, Void> {
        private final CompilationUnitTree unit;
        private final Trees trees;
        private final Map<String, String> imports = new HashMap<>();
        private final Deque<Context> contexts = new ArrayDeque<>();
        private final String packageName;
        private final String file;
        private final boolean indexOnly;

        private Scanner(CompilationUnitTree unit, Trees trees, boolean indexOnly) {
            this.unit = unit;
            this.trees = trees;
            this.indexOnly = indexOnly;
            packageName = unit.getPackageName().toString();
            file = sourceRoot.relativize(Path.of(unit.getSourceFile().toUri())).toString();
            unit.getImports().stream().filter(i -> !i.isStatic()).forEach(i -> {
                String name = i.getQualifiedIdentifier().toString();
                imports.put(name.substring(name.lastIndexOf('.') + 1), name);
            });
        }

        @Override
        public Void visitClass(ClassTree tree, Void unused) {
            String name = contexts.isEmpty() ? packageName + "." + tree.getSimpleName()
                : contexts.peek().name + "$" + tree.getSimpleName();
            String transformOwner = name.replace('.', '/');
            String redirectOwner = null;
            for (AnnotationTree annotation : tree.getModifiers().getAnnotations()) {
                String type = simple(annotation);
                Map<String, ExpressionTree> args = arguments(annotation);
                if (type.equals("Dasm") && args.containsKey("target")) transformOwner = ref(args.get("target"));
                if (type.equals("TypeRedirect") || type.equals("InterOwnerContainer")) redirectOwner = ref(args.get("from"));
                if (type.equals("IntraOwnerContainer")) redirectOwner = ref(args.get("value"));
                if (!indexOnly) {
                    String sourceType = type.equals("TransformFromClass") ? ref(args.get("value"))
                        : (type.equals("TypeRedirect") || type.equals("InterOwnerContainer") ? ref(args.get("from"))
                        : (type.equals("IntraOwnerContainer") ? ref(args.get("value")) : null));
                    if (sourceType != null && sourceType.startsWith("net/minecraft/")) {
                        counts.merge(type + " source type", 1, Integer::sum);
                        if (node(sourceType) == null) missing.add(finding(annotation, type, sourceType, "<class>"));
                    }
                }
            }
            if (redirectOwner != null) containerOwners.put(name.replace('.', '/'), redirectOwner);
            contexts.push(new Context(name, transformOwner, redirectOwner));
            try { return super.visitClass(tree, unused); }
            finally { contexts.pop(); }
        }

        @Override
        public Void visitMethod(MethodTree tree, Void unused) {
            checkAnnotations(tree.getModifiers().getAnnotations());
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(VariableTree tree, Void unused) {
            checkAnnotations(tree.getModifiers().getAnnotations());
            return super.visitVariable(tree, unused);
        }

        private void checkAnnotations(List<? extends AnnotationTree> annotations) {
            if (indexOnly || contexts.isEmpty()) return;
            for (AnnotationTree annotation : annotations) {
                String type = simple(annotation);
                boolean transform = type.equals("TransformFromMethod");
                boolean field = type.equals("FieldRedirect") || type.equals("FieldToMethodRedirect");
                if (type.equals("AddMethodToSets") || type.equals("AddFieldToSets") || type.equals("AddFieldToMethodToSets")) {
                    Map<String, ExpressionTree> args = arguments(annotation);
                    boolean addedField = !type.equals("AddMethodToSets");
                    String member = string(args.get(addedField ? "field" : "method"));
                    ExpressionTree container = args.get("containers");
                    List<? extends ExpressionTree> containers = container instanceof NewArrayTree array
                        ? array.getInitializers() : List.of(container);
                    for (ExpressionTree value : containers) {
                        String owner = containerOwners.get(classLiteral(value));
                        audit(annotation, type, owner, member, addedField, false);
                    }
                    continue;
                }
                if (!transform && !field && !type.equals("MethodRedirect") && !type.equals("ConstructorToFactoryRedirect")) continue;
                Map<String, ExpressionTree> args = arguments(annotation);
                String member = string(args.get("value"));
                String owner = transform ? (args.containsKey("owner") ? ref(args.get("owner")) : contexts.peek().transformOwner)
                    : contexts.peek().redirectOwner;
                audit(annotation, type + (transform ? (args.containsKey("owner") ? " explicit" : " implicit") : ""), owner, member, field, transform);
            }
        }

        private Map<String, Object> finding(AnnotationTree annotation, String type, String owner, String member) {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("file", file);
            result.put("line", unit.getLineMap().getLineNumber(trees.getSourcePositions().getStartPosition(unit, annotation)));
            result.put("annotation", type);
            result.put("owner", owner);
            result.put("member", member);
            return result;
        }

        private void audit(AnnotationTree annotation, String type, String owner, String member, boolean field, boolean transform) {
                Map<String, Object> finding = finding(annotation, type, owner, member);
                if (owner == null || member == null || !owner.startsWith("net/minecraft/")) {
                    skipped.add(finding);
                    return;
                }
                counts.merge(type, 1, Integer::sum);
                if (!exists(owner, member, field, !transform)) {
                    ClassNode n = node(owner);
                    String memberName = member.split(field ? ":" : "\\(", 2)[0];
                    finding.put("ownerExists", n != null);
                    if (n != null) finding.put("sameNameCandidates", field
                        ? n.fields.stream().filter(f -> f.name.equals(memberName)).map(f -> f.name + ":" + f.desc).toList()
                        : n.methods.stream().filter(m -> m.name.equals(memberName)).map(m -> m.name + m.desc).toList());
                    missing.add(finding);
                }
        }

        private String ref(ExpressionTree expression) {
            if (!(expression instanceof AnnotationTree annotation)) return null;
            Map<String, ExpressionTree> args = arguments(annotation);
            if (args.containsKey("string")) return string(args.get("string")).replace('.', '/');
            return classLiteral(args.get("value"));
        }

        private String classLiteral(ExpressionTree value) {
            if (value instanceof MemberSelectTree select && select.getIdentifier().contentEquals("class")) {
                String name = select.getExpression().toString();
                int dot = name.indexOf('.');
                String first = dot < 0 ? name : name.substring(0, dot);
                if (imports.containsKey(first)) {
                    String imported = imports.get(first);
                    String internal = imported.replace('.', '/') + (dot < 0 ? "" : name.substring(dot).replace('.', '$'));
                    if (classJars.containsKey(internal) || imported.startsWith("io.github.opencubicchunks")) return internal;
                    name = imported + (dot < 0 ? "" : name.substring(dot));
                }
                else if (!name.startsWith("net.") && !name.startsWith("io.") && !name.startsWith("java.")) name = packageName + "." + name;
                String internal = name.replace('.', '/');
                if (classJars.containsKey(internal)) return internal;
                while (internal.lastIndexOf('/') > 0) {
                    int separator = internal.lastIndexOf('/');
                    internal = internal.substring(0, separator) + "$" + internal.substring(separator + 1);
                    if (classJars.containsKey(internal)) return internal;
                }
                return name.replace('.', '/');
            }
            return null;
        }
    }

    private record Context(String name, String transformOwner, String redirectOwner) {}

    private static String simple(AnnotationTree annotation) {
        String name = annotation.getAnnotationType().toString();
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Map<String, ExpressionTree> arguments(AnnotationTree annotation) {
        Map<String, ExpressionTree> result = new HashMap<>();
        for (ExpressionTree argument : annotation.getArguments()) {
            if (argument instanceof AssignmentTree assignment) result.put(assignment.getVariable().toString(), assignment.getExpression());
            else result.put("value", argument);
        }
        return result;
    }

    private static String string(ExpressionTree expression) {
        if (expression instanceof LiteralTree literal && literal.getValue() instanceof String value) return value;
        if (expression instanceof BinaryTree binary && binary.getKind() == Tree.Kind.PLUS) {
            String left = string(binary.getLeftOperand()), right = string(binary.getRightOperand());
            return left == null || right == null ? null : left + right;
        }
        return null;
    }
}
