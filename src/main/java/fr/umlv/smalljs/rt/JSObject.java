package fr.umlv.smalljs.rt;

import static java.util.Objects.requireNonNull;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.SwitchPoint;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.function.Function;

public final class JSObject {
  private final JSObject proto;
  private final String name;
  private final MethodHandle mh;
  private final /*LinkedHashMap<String, Object>*/ArrayMap valueMap = new /*LinkedHashMap<>*/ArrayMap();
  private SwitchPoint switchPoint = new SwitchPoint();
  
  private static final class Undefined {
  	@Override public String toString() { return "undefined"; }
  }
  public static final Object UNDEFINED = new Undefined();
  
  private static final MethodHandle INVOKER;
  static {
    try {
      INVOKER = MethodHandles.lookup().findVirtual(Invoker.class, "invoke", MethodType.methodType(Object.class, Object.class, Object[].class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public interface Invoker {
    Object invoke(Object receiver, Object... args);
  }
  
  private JSObject(JSObject proto, String name, MethodHandle mh) {
    this.proto = proto;
    this.name = name;
    this.mh = mh;
  }

  private JSObject(JSObject proto, String name, Invoker invoker) {
    this(proto, name, INVOKER.bindTo(invoker).withVarargs(true));
  }
  
  public static JSObject newObject(JSObject proto) {
    return new JSObject(proto, "object", (_, _) -> { throw new Failure("object can not be invoked"); });
  }
  public static JSObject newEnv(JSObject parent) {
    return new JSObject(parent, "env", (_, _) -> { throw new Failure("env can not be invoked"); });
  }
  public static JSObject newFunction(String name, Invoker invoker) {
    requireNonNull(name);
    requireNonNull(invoker);
    var function =  new JSObject(null, "function " + name, invoker);
    function.register("apply", function);
    return function;
  }
  public static JSObject newFunction(String name, MethodHandle mh) {
    requireNonNull(name);
    requireNonNull(mh);
    var function = new JSObject(null, "function " + name, mh);
    function.register("apply", function);
    return function;
  }
  
  public String getName() {
		return name;
	}
  public MethodHandle getMethodHandle() {
    return mh;
  }
  public SwitchPoint getSwitchPoint() {
    return switchPoint;
  }
  public ArrayMap.Layout getLayout() {
    return valueMap.layout();
  }
  public Object fastAccess(int slot) {
    return valueMap.fastAccess(slot);
  }

  public Object invoke(Object receiver, Object[] args) {
    //System.err.println("invoke " + this + " " + receiver + " " + java.util.Arrays.toString(args));
    //System.err.println("invoke mh " + mh);

    if (!mh.isVarargsCollector() && args.length != mh.type().parameterCount() - 1) {
      throw new Failure("arguments doesn't match parameters count " + args.length + " " + (mh.type().parameterCount() - 1));
    }
    var array = new Object[args.length + 1];
    array[0] = receiver;
    System.arraycopy(args, 0, array, 1, args.length);
    try {
      return mh.invokeWithArguments(array);
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new Failure(e.getMessage(), e);
    }
  }
  
  public Object lookup(String key) {
    requireNonNull(key);
    var value = valueMap.get(key);
    if (value != null) {
      return value;
    }
    if (proto != null) {
      return proto.lookup(key);
    }
    return UNDEFINED;
  }

  public void register(String key, Object value) {
    requireNonNull(key);
    requireNonNull(value);
    valueMap.put(key, value);
    
    // broadcast change, not thread safe
    SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
    switchPoint = new SwitchPoint();
  }
  
  public int length() {
    return valueMap.size();
  }
  
  public JSObject mirror(Function<Object, Object> valueMapper) {
    requireNonNull(valueMapper);
    var mirror = newObject(null);
    valueMap.forEach((key, value) -> {
      mirror.register(key, valueMapper.apply(value));  
    });
    return mirror;
  }
  
  @Override
  public String toString() {
    var builder = new StringBuilder();
    toString(this, builder, Collections.newSetFromMap(new IdentityHashMap<>()));
    return builder.toString();
  }

  private static void toString(Object object, StringBuilder builder, Set<Object> seen) {
    if(object == null) {
      builder.append("null");
      return;
    }
    if (!seen.add(object)) {
      builder.append("...");
      if (object instanceof JSObject jsObject) {
        builder.append(" // ").append(jsObject.name);
      }
      return;
    }
    if (!(object instanceof JSObject jsObject)) {
      builder.append(object);
      return;
    }
    builder.append("{ // ").append(jsObject.name).append('\n');
    jsObject.valueMap.forEach((key, value) -> {
      builder.append("  ").append(key).append(": ");
      toString(value, builder, seen);
      builder.append("\n");
    });
    builder.append("  proto: ");
    toString(jsObject.proto, builder, seen);
    builder.append("\n");
    builder.append("}");
  }
}
