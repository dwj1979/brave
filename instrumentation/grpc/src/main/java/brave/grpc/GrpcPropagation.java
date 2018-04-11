package brave.grpc;

import brave.Tracing;
import brave.internal.MapPropagationFields;
import brave.internal.Nullable;
import brave.internal.PropagationFieldsFactory;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import brave.propagation.TraceContextOrSamplingFlags;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import java.util.List;
import java.util.Map;

/**
 * Use this to interop with processes that use <a href="https://github.com/census-instrumentation/opencensus-specs/blob/master/encodings/BinaryEncoding.md">
 * Census Binary Encoded Span Contexts</a>.
 *
 * <p>When true, the "grpc-trace-bin" metadata key is parsed on server calls. When missing, {@link Tracing#propagation() normal propagation}
 * is used. During client calls, "grpc-trace-bin" is speculatively written.
 */
public final class GrpcPropagation<K> implements Propagation<K> {

  /**
   * This creates a compatible metadata key based on Census, except this extracts a brave trace
   * context as opposed to a census span context
   */
  static final Metadata.Key<TraceContext> GRPC_TRACE_BIN =
      Metadata.Key.of("grpc-trace-bin", new TraceContextBinaryMarshaller());

  /** This stashes the tag context in "extra" so it isn't lost */
  static final Metadata.Key<Map<String, String>> GRPC_TAGS_BIN =
      Metadata.Key.of("grpc-tags-bin", new TagContextBinaryMarshaller());

  /** The census tag key corresponding to the {@link MethodDescriptor#fullMethodName}. */
  static final String RPC_METHOD = "method";

  public static Propagation.Factory newFactory(Propagation.Factory delegate) {
    if (delegate == null) throw new NullPointerException("delegate == null");
    return new Factory(delegate);
  }

  @Nullable static String parentMethod(TraceContext context) {
    Extra extra = findExtra(context);
    return extra != null ? extra.parentMethod : null;
  }

  static final class Factory extends Propagation.Factory {
    final Propagation.Factory delegate;
    final ExtraFactory extraFactory = new ExtraFactory();

    Factory(Propagation.Factory delegate) {
      this.delegate = delegate;
    }

    @Override public boolean supportsJoin() {
      return false;
    }

    @Override public boolean requires128BitTraceId() {
      return true;
    }

    @Override public final <K> Propagation<K> create(KeyFactory<K> keyFactory) {
      return new GrpcPropagation<>(this, keyFactory);
    }

    @Override public TraceContext decorate(TraceContext context) {
      TraceContext result = delegate.decorate(context);
      return extraFactory.decorate(result);
    }
  }

  final Propagation<K> delegate;
  final ExtraFactory extraFactory;

  GrpcPropagation(Factory factory, KeyFactory<K> keyFactory) {
    this.delegate = factory.delegate.create(keyFactory);
    this.extraFactory = factory.extraFactory;
  }

  @Override public List<K> keys() {
    return delegate.keys();
  }

  @Override public <C> Injector<C> injector(Setter<C, K> setter) {
    return new GrpcInjector<>(this, setter);
  }

  @Override public <C> Extractor<C> extractor(Getter<C, K> getter) {
    return new GrpcExtractor<>(this, getter);
  }

  static final class GrpcInjector<C, K> implements Injector<C> {
    final Injector<C> delegate;
    final Propagation.Setter<C, K> setter;

    GrpcInjector(GrpcPropagation<K> propagation, Setter<C, K> setter) {
      this.delegate = propagation.delegate.injector(setter);
      this.setter = setter;
    }

    @Override public void inject(TraceContext traceContext, C carrier) {
      if (carrier instanceof Metadata) {
        ((Metadata) carrier).put(GRPC_TRACE_BIN, traceContext);
        Extra extra = findExtra(traceContext);
        if (extra != null) ((Metadata) carrier).put(GRPC_TAGS_BIN, extra.toMap());
      }
      delegate.inject(traceContext, carrier);
    }
  }

  @Nullable static Extra findExtra(TraceContext traceContext) {
    List<Object> extra = traceContext.extra();
    for (int i = 0, length = extra.size(); i < length; i++) {
      Object next = extra.get(i);
      if (next instanceof Extra) {
        return (Extra) next;
      }
    }
    return null;
  }

  static final class GrpcExtractor<C, K> implements Extractor<C> {
    final GrpcPropagation<K> propagation;
    final Extractor<C> delegate;
    final Propagation.Getter<C, K> getter;

    GrpcExtractor(GrpcPropagation<K> propagation, Getter<C, K> getter) {
      this.propagation = propagation;
      this.delegate = propagation.delegate.extractor(getter);
      this.getter = getter;
    }

    @Override public TraceContextOrSamplingFlags extract(C carrier) {
      Extra extra = null;
      if (carrier instanceof Metadata) {
        TraceContext extractedTrace = ((Metadata) carrier).get(GRPC_TRACE_BIN);
        Map<String, String> extractedTags = ((Metadata) carrier).get(GRPC_TAGS_BIN);
        if (extractedTags != null) {
          extra = new Extra(extractedTags, extractedTags.remove(RPC_METHOD));
        }
        if (extractedTrace != null) {
          if (extra == null) return TraceContextOrSamplingFlags.create(extractedTrace);
          return TraceContextOrSamplingFlags.newBuilder()
              .addExtra(extra)
              .context(extractedTrace)
              .build();
        }
      }
      TraceContextOrSamplingFlags result = delegate.extract(carrier);
      if (extra == null) return result;
      return result.toBuilder().addExtra(extra).build();
    }
  }

  static final class ExtraFactory extends PropagationFieldsFactory<Extra> {
    @Override protected Class type() {
      return Extra.class;
    }

    @Override protected Extra create() {
      return new Extra();
    }

    @Override protected Extra create(Extra parent) {
      return new Extra(parent);
    }
  }

  static final class Extra extends MapPropagationFields {
    final String parentMethod;

    Extra() {
      parentMethod = null;
    }

    Extra(Extra parent) {
      super(parent);
      parentMethod = null;
    }

    Extra(Map<String, String> extracted, String parentMethod) {
      super(extracted);
      this.parentMethod = parentMethod;
    }
  }
}
