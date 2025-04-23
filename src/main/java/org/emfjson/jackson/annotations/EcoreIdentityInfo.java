package org.emfjson.jackson.annotations;

import org.eclipse.emf.ecore.EObject;
import org.emfjson.jackson.utils.ValueReader;
import org.emfjson.jackson.utils.ValueWriter;

import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;

public class EcoreIdentityInfo<T extends EObject, R> {

    public static final String PROPERTY = "@id";

    private final String property;
    private final ValueReader<T, String> valueReader;
    private final ValueWriter<? super T, ? super R> valueWriter;

    // Default fallback reader: identity that throws
    private static final ValueReader<EObject, String> defaultValueReader = new ValueReader<EObject, String>() {
        @Override
        public String readValue(EObject value, DeserializationContext context) {
            throw new UnsupportedOperationException("No valueReader provided for EcoreIdentityInfo");
        }
    };

    // Default fallback writer: returns null
    private static final ValueWriter<EObject, Object> defaultValueWriter = new ValueWriter<>() {
        @Override
        public Object writeValue(EObject value, SerializerProvider context) {
            return null;
        }
    };

    // No-arg constructor
    public EcoreIdentityInfo() {
        this(null, null, null);
    }

    // Constructor with property only
    public EcoreIdentityInfo(String property) {
        this(property, null, null);
    }

    // Constructor with ValueReader only
    public EcoreIdentityInfo(String property, ValueReader<T, String> valueReader) {
        this(property, valueReader, null);
    }

    // Constructor with ValueWriter only
    public EcoreIdentityInfo(String property, ValueWriter<? super T, ? super R> valueWriter) {
        this(property, null, valueWriter);
    }

    // Full constructor
    @SuppressWarnings("unchecked")
    public EcoreIdentityInfo(String property,
                             ValueReader<T, String> valueReader,
                             ValueWriter<? super T, ? super R> valueWriter) {
        this.property = property == null ? PROPERTY : property;
        this.valueReader = valueReader != null
                ? valueReader
                : (ValueReader<T, String>) defaultValueReader;
        this.valueWriter = valueWriter != null
                ? valueWriter
                : (ValueWriter<? super T, ? super R>) defaultValueWriter;
    }

    // Accessors
    public String getProperty() {
        return property;
    }

    public ValueReader<T, String> getValueReader() {
        return valueReader;
    }

    public ValueWriter<? super T, ? super R> getValueWriter() {
        return valueWriter;
    }
}
