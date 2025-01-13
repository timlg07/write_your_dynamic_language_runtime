package fr.umlv.smalljs.jvminterp;

import static java.lang.invoke.MethodType.genericMethodType;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.objectweb.asm.Opcodes.POP;
import static org.objectweb.asm.Opcodes.ACC_PUBLIC;
import static org.objectweb.asm.Opcodes.ACC_STATIC;
import static org.objectweb.asm.Opcodes.ACC_SUPER;
import static org.objectweb.asm.Opcodes.ARETURN;
import static org.objectweb.asm.Opcodes.ASTORE;
import static org.objectweb.asm.Opcodes.H_INVOKESTATIC;
import static org.objectweb.asm.Opcodes.V21;

import java.io.PrintWriter;
import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodHandles.Lookup;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.util.CheckClassAdapter;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.rt.JSObject;

public final class ByteCodeRewriter {
    public static JSObject createFunction(String name, List<String> parameters, Block body, JSObject global) {
        var env = JSObject.newEnv(null);

        env.register("this", 0);
        for (String parameter : parameters) {
            env.register(parameter, env.length());
        }
        var parameterCount = env.length();
        visitVariable(body, env);
        var localVariableCount = env.length();

        var cv = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        cv.visit(V21, ACC_PUBLIC | ACC_SUPER, "script", null, "java/lang/Object", null);
        cv.visitSource("script", null);

        var methodType = genericMethodType(1 + parameters.size());
        var desc = methodType.toMethodDescriptorString();
        var mv = cv.visitMethod(ACC_PUBLIC | ACC_STATIC, name, desc, null, null);
        mv.visitCode();

        //initialize local variables to undefined by default
        for(var i = parameterCount; i < localVariableCount; i++) {
          mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
          mv.visitVarInsn(ASTORE, i);
        }

        var dictionary = new FunDictionary();
        visit(body, env, mv, dictionary);

        mv.visitLdcInsn(new ConstantDynamic("undefined", "Ljava/lang/Object;", BSM_UNDEFINED));
        mv.visitInsn(ARETURN);
        mv.visitMaxs(0, 0);
        mv.visitEnd();

        var instrs = cv.toByteArray();
        dumpBytecode(instrs);

        var functionClassLoader = new FunClassLoader(dictionary, global);
        var type = functionClassLoader.createClass("script", instrs);

        MethodHandle mh;
        try {
            mh = MethodHandles.lookup().findStatic(type, name, methodType);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new AssertionError(e);
        }

        return JSObject.newFunction(name, mh);
    }

    private static void dumpBytecode(byte[] array) {
      var reader = new ClassReader(array);
      CheckClassAdapter.verify(reader, true, new PrintWriter(System.err, false, UTF_8));
    }

    private static void visitVariable(Expr expression, JSObject env) {
      switch (expression) {
        case Block(List<Expr> instrs, int lineNumber) -> {
          for (Expr instr : instrs) {
            visitVariable(instr, env);
          }
        }
        case Literal<?>(Object value, int lineNumber) -> {
          // do nothing
        }
        case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
          // do nothing
        }
        case LocalVarAccess(String name, int lineNumber) -> {
          // do nothing
        }
        case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
          if (declaration) {
            env.register(name, env.length());
          }
        }
        case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
          // do nothing
        }
        case Return(Expr expr, int lineNumber) -> {
          // do nothing
        }
        case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
          visitVariable(trueBlock, env);
          visitVariable(falseBlock, env);
        }
        case New(Map<String, Expr> initMap, int lineNumber) -> {
          // do nothing
        }
        case FieldAccess(Expr receiver, String name, int lineNumber) -> {
          // do nothing
        }
        case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
          // do nothing
        }
        case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
          // do nothing
        }
      };
    }

    private static Handle bsm(String name, Class<?> returnType, Class<?>... parameterTypes) {
        return new Handle(H_INVOKESTATIC,
                RT_NAME, name,
                MethodType.methodType(returnType, parameterTypes).toMethodDescriptorString(), false);
    }

    private static final String JSOBJECT = JSObject.class.getName().replace('.', '/');
    private static final String RT_NAME = RT.class.getName().replace('.', '/');
    private static final Handle BSM_UNDEFINED = bsm("bsm_undefined", Object.class, Lookup.class, String.class, Class.class);
    private static final Handle BSM_CONST = bsm("bsm_const", Object.class, Lookup.class, String.class, Class.class, int.class);
    private static final Handle BSM_FUNCALL = bsm("bsm_funcall", CallSite.class, Lookup.class, String.class, MethodType.class);
    private static final Handle BSM_LOOKUP = bsm("bsm_lookup", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_FUN = bsm("bsm_fun", Object.class, Lookup.class, String.class, Class.class, int.class);
    private static final Handle BSM_REGISTER = bsm("bsm_register", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_TRUTH = bsm("bsm_truth", CallSite.class, Lookup.class, String.class, MethodType.class);
    private static final Handle BSM_GET = bsm("bsm_get", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_SET = bsm("bsm_set", CallSite.class, Lookup.class, String.class, MethodType.class, String.class);
    private static final Handle BSM_METHODCALL = bsm("bsm_methodcall", CallSite.class, Lookup.class, String.class, MethodType.class);

    private static void visit(Expr expression, JSObject env, MethodVisitor mv, FunDictionary dictionary) {
      switch(expression) {
        case Block(List<Expr> instrs, int lineNumber) -> {
            for (var instr : instrs) {
                // generate line numbers
                var label = new Label();
                mv.visitLineNumber(lineNumber, label);
                // visit it
                visit(instr, env, mv, dictionary);
                // if not an instruction and generate a POP
                if (!(instr instanceof Expr.Instr)) {
                    mv.visitInsn(POP);
                }
            }
        }
        case Literal<?>(Object value, int lineNumber) -> {
          // switch on the value
            switch (value) {
                case Integer i -> {
                    // if it's an Integer, wrap it into a ConstantDynamic because the JVM doesn't have a primitive for boxed integer
                    mv.visitLdcInsn(new ConstantDynamic("integerConst", "Ljava/lang/Object;", BSM_CONST, i));
                }
                case String s -> {
                    // if it's a String, use visitLDCInstr
                    mv.visitLdcInsn(s);
                }
                // otherwise report an error
                default -> throw new IllegalStateException("Unexpected value: " + value);
            }
        }
        case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO FunCall");
          // visit the qualifier
          // load "this"
          // for each argument, visit it
          // the name of the invokedynamic is either "builtincall" or "funcall"
          // generate an invokedynamic with the right name
        }
        case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO LocalVarAssignment");
          // visit the expression
          // lookup that name in the environment
          // if it does not exist throw a Failure
          // otherwise STORE the top of the stack at the local variable slot
        }
        case LocalVarAccess(String name, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO LocalVarAccess");
          // lookup to find if it's a local var access or a lookup access
          // if undefined
          //  generate an invokedynamic doing a lookup
          // otherwise
          //  load the local variable at the slot
        }
        case Fun fun -> {
          Optional<String> optName = fun.optName();
          throw new UnsupportedOperationException("TODO Fun");
          // register the fun inside the fun directory and get the corresponding id
          // emit a LDC to load the function corresponding to the id at runtime
          // generate an invokedynamic doing a register with the function name
        }
        case Return(Expr expr, int lineNumber) -> {
          // throw new UnsupportedOperationException("TODO Return");
          // visit the return expression
          // generate the bytecode
        }
        case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO If");
          // visit the condition
          // generate an invokedynamic to transform an Object to a boolean using BSM_TRUTH
          // visit the true block
          // visit the false block
        }
        case New(Map<String, Expr> initMap, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO New");
          // call newObject with an INVOKESTATIC
          // for each initialization expression
            // generate a string with the key
            // call register on the JSObject
        }
        case FieldAccess(Expr receiver, String name, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO FieldAccess");
          // visit the receiver
          // generate an invokedynamic that goes a get through BSM_GET
        }
        case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO FieldAssignment");
          // visit the receiver
          // visit the expression
        }
        case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
          throw new UnsupportedOperationException("TODO MethodCall");
          // visit the receiver
          // for each argument
            // visit the argument
          // generate an invokedynamic that call BSM_METHODCALL
        }
      }
    }
}
