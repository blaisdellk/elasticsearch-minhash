package org.codelibs.elasticsearch.minhash.index.mapper;

import static org.elasticsearch.common.xcontent.support.XContentMapValues.nodeBooleanValue;
import static org.elasticsearch.index.mapper.core.TypeParsers.parseField;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.lucene.document.Field;
import org.apache.lucene.document.FieldType;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.store.ByteArrayDataOutput;
import org.apache.lucene.util.BytesRef;
import org.codelibs.elasticsearch.minhash.MinHash;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.ElasticsearchParseException;
import org.elasticsearch.common.Base64;
import org.elasticsearch.common.Nullable;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.bytes.BytesArray;
import org.elasticsearch.common.bytes.BytesReference;
import org.elasticsearch.common.compress.CompressorFactory;
import org.elasticsearch.common.hppc.ObjectArrayList;
import org.elasticsearch.common.io.stream.BytesStreamOutput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.unit.ByteSizeValue;
import org.elasticsearch.common.util.CollectionUtils;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.index.analysis.NamedAnalyzer;
import org.elasticsearch.index.codec.docvaluesformat.DocValuesFormatProvider;
import org.elasticsearch.index.codec.postingsformat.PostingsFormatProvider;
import org.elasticsearch.index.fielddata.FieldDataType;
import org.elasticsearch.index.mapper.Mapper;
import org.elasticsearch.index.mapper.MapperParsingException;
import org.elasticsearch.index.mapper.MergeContext;
import org.elasticsearch.index.mapper.MergeMappingException;
import org.elasticsearch.index.mapper.ParseContext;
import org.elasticsearch.index.mapper.core.AbstractFieldMapper;
import org.elasticsearch.index.mapper.core.NumberFieldMapper;

public class MinHashFieldMapper extends AbstractFieldMapper<BytesReference> {

    public static final String CONTENT_TYPE = "minhash";

    public static MinHashFieldMapper.Builder minhashField(final String name) {
        return new MinHashFieldMapper.Builder(name);
    }

    public static class Defaults extends AbstractFieldMapper.Defaults {
        public static final long COMPRESS_THRESHOLD = -1;

        public static final FieldType FIELD_TYPE = new FieldType(
                AbstractFieldMapper.Defaults.FIELD_TYPE);

        static {
            FIELD_TYPE.setStored(true);
            FIELD_TYPE.setIndexed(false);
            FIELD_TYPE.freeze();
        }
    }

    public static class Builder extends
            AbstractFieldMapper.Builder<Builder, MinHashFieldMapper> {

        private Boolean compress = null;

        private long compressThreshold = Defaults.COMPRESS_THRESHOLD;

        private NamedAnalyzer minhashAnalyzer;

        public Builder(final String name) {
            super(name, new FieldType(Defaults.FIELD_TYPE));
            builder = this;
        }

        public Builder compress(final boolean compress) {
            this.compress = compress;
            return this;
        }

        public Builder compressThreshold(final long compressThreshold) {
            this.compressThreshold = compressThreshold;
            return this;
        }

        @Override
        public MinHashFieldMapper build(final BuilderContext context) {
            return new MinHashFieldMapper(buildNames(context), fieldType,
                    docValues, compress, compressThreshold, postingsProvider,
                    docValuesProvider, fieldDataSettings,
                    multiFieldsBuilder.build(this, context), copyTo,
                    minhashAnalyzer);
        }

        public void minhashAnalyzer(final NamedAnalyzer minhashAnalyzer) {
            this.minhashAnalyzer = minhashAnalyzer;
        }

    }

    public static class TypeParser implements Mapper.TypeParser {
        @Override
        public Mapper.Builder parse(final String name,
                final Map<String, Object> node,
                final ParserContext parserContext)
                throws MapperParsingException {
            final MinHashFieldMapper.Builder builder = minhashField(name);
            parseField(builder, name, node, parserContext);
            for (final Map.Entry<String, Object> entry : node.entrySet()) {
                final String fieldName = Strings.toUnderscoreCase(entry
                        .getKey());
                final Object fieldNode = entry.getValue();
                if (fieldName.equals("compress") && fieldNode != null) {
                    builder.compress(nodeBooleanValue(fieldNode));
                } else if (fieldName.equals("compress_threshold")
                        && fieldNode != null) {
                    if (fieldNode instanceof Number) {
                        builder.compressThreshold(((Number) fieldNode)
                                .longValue());
                        builder.compress(true);
                    } else {
                        builder.compressThreshold(ByteSizeValue
                                .parseBytesSizeValue(fieldNode.toString())
                                .bytes());
                        builder.compress(true);
                    }
                } else if (fieldName.equals("minhash_analyzer")
                        && fieldNode != null) {
                    final NamedAnalyzer analyzer = parserContext
                            .analysisService().analyzer(fieldNode.toString());
                    builder.minhashAnalyzer(analyzer);
                }
            }
            return builder;
        }
    }

    private Boolean compress;

    private long compressThreshold;

    private NamedAnalyzer minhashAnalyzer;

    protected MinHashFieldMapper(final Names names, final FieldType fieldType,
            final Boolean docValues, final Boolean compress,
            final long compressThreshold,
            final PostingsFormatProvider postingsProvider,
            final DocValuesFormatProvider docValuesProvider,
            @Nullable final Settings fieldDataSettings,
            final MultiFields multiFields, final CopyTo copyTo,
            final NamedAnalyzer minhashAnalyzer) {
        super(names, 1.0f, fieldType, docValues, null, null, postingsProvider,
                docValuesProvider, null, null, fieldDataSettings, null,
                multiFields, copyTo);
        this.compress = compress;
        this.compressThreshold = compressThreshold;
        this.minhashAnalyzer = minhashAnalyzer;
    }

    @Override
    public FieldType defaultFieldType() {
        return Defaults.FIELD_TYPE;
    }

    @Override
    public FieldDataType defaultFieldDataType() {
        return new FieldDataType("minhash");
    }

    @Override
    public Object valueForSearch(final Object value) {
        return value(value);
    }

    @Override
    public BytesReference value(final Object value) {
        if (value == null) {
            return null;
        }

        BytesReference bytes;
        if (value instanceof BytesRef) {
            bytes = new BytesArray((BytesRef) value);
        } else if (value instanceof BytesReference) {
            bytes = (BytesReference) value;
        } else if (value instanceof byte[]) {
            bytes = new BytesArray((byte[]) value);
        } else {
            try {
                bytes = new BytesArray(Base64.decode(value.toString()));
            } catch (final IOException e) {
                throw new ElasticsearchParseException(
                        "failed to convert bytes", e);
            }
        }
        try {
            return CompressorFactory.uncompressIfNeeded(bytes);
        } catch (final IOException e) {
            throw new ElasticsearchParseException(
                    "failed to decompress source", e);
        }
    }

    @Override
    protected void parseCreateField(final ParseContext context,
            final List<Field> fields) throws IOException {
        if (!fieldType().stored() && !hasDocValues()) {
            return;
        }
        String text = context.parseExternalValue(String.class);
        if (text == null) {
            if (context.parser().currentToken() == XContentParser.Token.VALUE_NULL) {
                return;
            } else {
                text = context.parser().textOrNull();
            }
        }
        if (text == null) {
            return;
        }

        byte[] value = MinHash.calcMinHash(minhashAnalyzer, text);
        if (value == null) {
            return;
        }

        if (compress != null && compress
                && !CompressorFactory.isCompressed(value, 0, value.length)) {
            if (compressThreshold == -1 || value.length > compressThreshold) {
                final BytesStreamOutput bStream = new BytesStreamOutput();
                final StreamOutput stream = CompressorFactory
                        .defaultCompressor().streamOutput(bStream);
                stream.writeBytes(value, 0, value.length);
                stream.close();
                value = bStream.bytes().toBytes();
            }
        }
        if (fieldType().stored()) {
            fields.add(new Field(names.indexName(), value, fieldType));
        }

        if (hasDocValues()) {
            CustomBinaryDocValuesField field = (CustomBinaryDocValuesField) context
                    .doc().getByKey(names().indexName());
            if (field == null) {
                field = new CustomBinaryDocValuesField(names().indexName(),
                        value);
                context.doc().addWithKey(names().indexName(), field);
            } else {
                field.add(value);
            }
        }

    }

    @Override
    protected String contentType() {
        return CONTENT_TYPE;
    }

    @Override
    protected void doXContentBody(final XContentBuilder builder,
            final boolean includeDefaults, final Params params)
            throws IOException {
        super.doXContentBody(builder, includeDefaults, params);
        builder.field("minhash_analyzer", minhashAnalyzer.name());
        if (compress != null) {
            builder.field("compress", compress);
        } else if (includeDefaults) {
            builder.field("compress", false);
        }
        if (compressThreshold != -1) {
            builder.field("compress_threshold", new ByteSizeValue(
                    compressThreshold).toString());
        } else if (includeDefaults) {
            builder.field("compress_threshold", -1);
        }
    }

    @Override
    public void merge(final Mapper mergeWith, final MergeContext mergeContext)
            throws MergeMappingException {
        super.merge(mergeWith, mergeContext);
        if (!this.getClass().equals(mergeWith.getClass())) {
            return;
        }

        final MinHashFieldMapper sourceMergeWith = (MinHashFieldMapper) mergeWith;
        if (!mergeContext.mergeFlags().simulate()) {
            if (sourceMergeWith.compress != null) {
                compress = sourceMergeWith.compress;
            }
            if (sourceMergeWith.compressThreshold != -1) {
                compressThreshold = sourceMergeWith.compressThreshold;
            }
        }
    }

    public static class CustomBinaryDocValuesField extends
            NumberFieldMapper.CustomNumericDocValuesField {

        public static final FieldType TYPE = new FieldType();
        static {
            TYPE.setDocValueType(FieldInfo.DocValuesType.BINARY);
            TYPE.freeze();
        }

        private final ObjectArrayList<byte[]> bytesList;

        private int totalSize = 0;

        public CustomBinaryDocValuesField(final String name, final byte[] bytes) {
            super(name);
            bytesList = new ObjectArrayList<>();
            add(bytes);
        }

        public void add(final byte[] bytes) {
            bytesList.add(bytes);
            totalSize += bytes.length;
        }

        @Override
        public BytesRef binaryValue() {
            try {
                CollectionUtils.sortAndDedup(bytesList);
                final int size = bytesList.size();
                final byte[] bytes = new byte[totalSize + (size + 1) * 5];
                final ByteArrayDataOutput out = new ByteArrayDataOutput(bytes);
                out.writeVInt(size); // write total number of values
                for (int i = 0; i < size; i++) {
                    final byte[] value = bytesList.get(i);
                    final int valueLength = value.length;
                    out.writeVInt(valueLength);
                    out.writeBytes(value, 0, valueLength);
                }
                return new BytesRef(bytes, 0, out.getPosition());
            } catch (final IOException e) {
                throw new ElasticsearchException("Failed to get binary value",
                        e);
            }

        }
    }
}