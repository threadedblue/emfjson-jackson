/*******************************************************************************
 * Copyright (c) 2019-2022 Guillaume Hillairet and others.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License v. 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0, or the MIT License which is
 * available at https://opensource.org/licenses/MIT.
 *
 * SPDX-License-Identifier: EPL-2.0 OR MIT
 *******************************************************************************/

package org.eclipse.emfcloud.jackson.databind.property;

import static org.eclipse.emfcloud.jackson.annotations.JsonAnnotations.getElementName;
import static org.eclipse.emfcloud.jackson.annotations.JsonAnnotations.isRawValue;
import static org.eclipse.emfcloud.jackson.module.EMFModule.Feature.OPTION_SERIALIZE_DEFAULT_VALUE;

import java.io.IOException;

import org.eclipse.emf.common.util.EList;
import org.eclipse.emf.ecore.EClass;
import org.eclipse.emf.ecore.EDataType;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.EStructuralFeature;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.util.FeatureMap;
import org.eclipse.emfcloud.jackson.databind.EMFContext;
import org.eclipse.emfcloud.jackson.databind.deser.RawDeserializer;
import org.eclipse.emfcloud.jackson.databind.deser.ReferenceEntries;
import org.eclipse.emfcloud.jackson.databind.deser.ReferenceEntry;
import org.eclipse.emfcloud.jackson.databind.type.FeatureKind;
import org.hl7.fhir.FhirFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.ObjectCodec;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.impl.UnknownSerializer;
import com.fasterxml.jackson.databind.ser.std.RawSerializer;

public class EObjectFeatureProperty extends EObjectProperty {

	private static final Logger log = LoggerFactory.getLogger(EObjectFeatureProperty.class);

   private final EStructuralFeature feature;
   private final JavaType javaType;
   private final boolean defaultValues;

   private JsonSerializer<Object> serializer;
   private JsonDeserializer<Object> deserializer;

   public EObjectFeatureProperty(final EStructuralFeature feature, final JavaType type, final int features) {
      super(getElementName(feature, features));

      this.feature = feature;
      this.javaType = type;
      this.defaultValues = OPTION_SERIALIZE_DEFAULT_VALUE.enabledIn(features);

      if (isRawValue(feature)) {
         this.serializer = new RawSerializer<>(String.class);
         this.deserializer = new RawDeserializer();
      }
   }

   @Override
   @SuppressWarnings({ "checkstyle:cyclomaticComplexity", "checkstyle:fallThrough" })
   public void deserializeAndSet(final JsonParser jp, final EObject current, final DeserializationContext ctxt,
      final Resource resource)
      throws IOException {
      if (deserializer == null) {
         deserializer = ctxt.findContextualValueDeserializer(javaType, null);
      }
      JsonToken token = null;

      if (jp.getCurrentToken() == JsonToken.FIELD_NAME) {
         log.trace("Inside FIELD_NAME=={}", jp.currentName());
         token = jp.nextToken();
      }

      if (jp.getCurrentToken() == JsonToken.VALUE_NULL) {
         return;
      }

      boolean isMap = false;
      switch (FeatureKind.get(feature)) {
         case MAP:
            isMap = true;
            //$FALL-THROUGH$
         case MANY_CONTAINMENT:
         case SINGLE_CONTAINMENT: {
            EMFContext.setFeature(ctxt, feature);
            EMFContext.setParent(ctxt, current);
         }
         //$FALL-THROUGH$
         case SINGLE_ATTRIBUTE:
         case MANY_ATTRIBUTE: {
            if (feature.getEType() instanceof EDataType) {
               EMFContext.setDataType(ctxt, feature.getEType());
               Class<?> clazz = feature.getEType().getInstanceClass();
               if (clazz != null && FeatureMap.Entry.class.isAssignableFrom(clazz)) {
                  // we need the parent to construct the feature map entry with correct feature
                  EMFContext.setParent(ctxt, current);
               }
            }

            log.warn("FIELD: {}", feature.getName());
            log.warn("TOKEN: {}", jp.getCurrentToken());
            
            if (jp.getCurrentToken() == JsonToken.FIELD_NAME) {
               jp.nextToken(); // ✅ Advance to START_ARRAY
            }
            deserializeValue(jp, current, ctxt, isMap);
         }
            break;
         case MANY_REFERENCE:
         case SINGLE_REFERENCE: {
            EMFContext.setFeature(ctxt, feature);
            EMFContext.setParent(ctxt, current);

            deserializeAsReference(jp, ctxt);
         }
            break;
         default:
            break;
      }
   }

   protected void deserializeAsReference(final JsonParser jp, final DeserializationContext ctxt)
      throws IOException, JsonProcessingException {
      ReferenceEntries entries = EMFContext.getEntries(ctxt);
      if (feature.isMany()) {
         deserializer.deserialize(jp, ctxt, entries.entries());
      } else {
         Object value = deserializer.deserialize(jp, ctxt);
         if (entries != null && value instanceof ReferenceEntry) {
            entries.entries().add((ReferenceEntry) value);
         }
      }
   }

   protected void deserializeValue(final JsonParser jp, final EObject current, final DeserializationContext ctxt,
    final boolean isMap) throws IOException, JsonProcessingException {

      log.trace("deserializeValue==>");

      JsonToken token = jp.getCurrentToken();

      if (feature.isMany()) {
         EList<Object> list = (EList<Object>) current.eGet(feature);
         if (list == null) {
            throw new JsonParseException(jp, "Target list for feature '" + feature.getName() + "' is null");
         }
   
         if (token != JsonToken.START_ARRAY && !isMap) {
            log.debug("token1=={}", jp.getCurrentToken());
            log.debug("token2=={}", token.name());
            log.debug("feature=={}", feature.getName());
            throw new JsonParseException(jp, "Expected START_ARRAY token, got " + token);
         }

         if (isFHIRStringListFeature(feature)) {
            deserializeFHIRStringList(jp, current, ctxt);
         } else if (isFHIRCanonicalListFeature(feature)) {
            deserializeFHIRCanonicalList(jp, current, ctxt);
         } else if (isContainedResourceListFeature(feature)) {
            deserializeContainedResourceList(jp, current, ctxt);
         }  else {
            deserializer.deserialize(jp, ctxt, current.eGet(feature));
         }
      } else {
         Object value = deserializer.deserialize(jp, ctxt);

         if (value != null) {
            current.eSet(feature, value);
         }
      }
   }

   private boolean isFHIRStringListFeature(EStructuralFeature feature) {

      log.trace("isFHIRStringListFeature=={}", feature.getName());

      return feature.getEType() != null
         && "String".equals(feature.getEType().getName())
         && "http://hl7.org/fhir".equals(feature.getEType().getEPackage().getNsURI());
   }

   protected void deserializeFHIRStringList(JsonParser jp, EObject current, DeserializationContext ctxt) throws IOException {
 
      log.trace("deserializeFHIRStringList==>");

     EList<Object> list = (EList<Object>) current.eGet(feature);

      if (jp.isExpectedStartArrayToken()) {
         ensureStringElementDeserializer(ctxt);

         while (jp.nextToken() != JsonToken.END_ARRAY) {
            if (jp.getCurrentToken() == JsonToken.VALUE_STRING) {
               EObject fhirString = (EObject) stringElementDeserializer.deserialize(jp, ctxt);
               list.add(fhirString);
            } else {
               throw new JsonParseException(jp, "Expected VALUE_STRING inside array for FHIR String");
            }
         }
      } else {
         throw new JsonParseException(jp, "Expected START_ARRAY token for FHIR String list field");
      }
   }

   private boolean isFHIRCanonicalListFeature(EStructuralFeature feature) {
      return feature.getEType() != null
         && "Canonical".equals(feature.getEType().getName())
         && "http://hl7.org/fhir".equals(feature.getEType().getEPackage().getNsURI());
   }

   @SuppressWarnings("unchecked")
   private void deserializeFHIRCanonicalList(JsonParser jp, EObject current, DeserializationContext ctxt) throws IOException {
      EList<Object> list = (EList<Object>) current.eGet(feature);

      ObjectCodec codec = jp.getCodec();
      JsonParser parser = jp;

      if (parser.isExpectedStartArrayToken()) {
         while (parser.nextToken() != JsonToken.END_ARRAY) {
               if (parser.getCurrentToken() == JsonToken.VALUE_STRING) {
                  String canonicalValue = parser.getValueAsString();

                  EObject canonical = FhirFactory.eINSTANCE.createCanonical();

                  canonical.eSet(canonical.eClass().getEStructuralFeature("value"), canonicalValue);

                  list.add(canonical);
               } else {
                  throw new JsonParseException(parser, "Expected VALUE_STRING inside array for FHIR Canonical");
               }
         }
      } else {
         throw new JsonParseException(parser, "Expected START_ARRAY token for FHIR Canonical list field");
      }
   }

   private boolean isContainedResourceListFeature(EStructuralFeature feature) {
      return "contained".equals(feature.getName())
         && "ResourceContainer".equals(feature.getEType().getName())
         && "http://hl7.org/fhir".equals(feature.getEType().getEPackage().getNsURI());
   }

   @SuppressWarnings("unchecked")
   private void deserializeContainedResourceList(JsonParser jp, EObject current, DeserializationContext ctxt) throws IOException {
      log.trace("deserializeContainedResourceList==>");

      EList<Object> list = (EList<Object>) current.eGet(feature);
      ObjectCodec codec = jp.getCodec();

      if (!jp.isExpectedStartArrayToken()) {
         throw new JsonParseException(jp, "Expected START_ARRAY token for 'contained'");
      }

      while (jp.nextToken() != JsonToken.END_ARRAY) {
         JsonNode node = jp.readValueAsTree();
         JsonNode typeNode = node.get("resourceType");
         if (typeNode == null || !typeNode.isTextual()) {
            throw new JsonParseException(jp, "Missing or invalid resourceType in contained entry");
         }

         String resourceType = typeNode.asText();
         EClass targetEClass = (EClass) FhirFactory.eINSTANCE.getEPackage().getEClassifier(resourceType);
         if (targetEClass == null) {
            throw new JsonParseException(jp, "Unknown FHIR resourceType: " + resourceType);
         }

         EObject resource = FhirFactory.eINSTANCE.create(targetEClass);
         JsonParser resourceParser = node.traverse(codec);
         resourceParser.nextToken();

         if (codec instanceof com.fasterxml.jackson.databind.ObjectMapper) {
            ((com.fasterxml.jackson.databind.ObjectMapper) codec)
               .readerForUpdating(resource).readValue(resourceParser);
         } else {
            codec.readValue(resourceParser, resource.getClass());
         }

         EObject container = FhirFactory.eINSTANCE.createResourceContainer();
         for (EStructuralFeature ref : container.eClass().getEAllStructuralFeatures()) {
            if (ref.getEType() == targetEClass) {
               container.eSet(ref, resource);
               break;
            }
         }

         list.add(container);
      }
   }

   private JsonDeserializer<Object> stringElementDeserializer;

   private void ensureStringElementDeserializer(DeserializationContext ctxt) throws JsonMappingException {
 
      log.trace("ensureStringElementDeserializer==>");

      if (stringElementDeserializer == null) {
         JavaType type = ctxt.constructType(org.hl7.fhir.String.class);
         stringElementDeserializer = ctxt.findContextualValueDeserializer(type, null);
      }
   }

   @Override
   public void serialize(final EObject bean, final JsonGenerator jg, final SerializerProvider provider)
      throws IOException {
      if (serializer == null) {
         serializer = provider.findValueSerializer(javaType);
      }

      EMFContext.setParent(provider, bean);
      EMFContext.setFeature(provider, feature);

      if (bean.eIsSet(feature)) {
         Object value = bean.eGet(feature, false);

         jg.writeFieldName(getFieldName());

         if (serializer instanceof UnknownSerializer) {
            JsonSerializer<Object> other = provider.findValueSerializer(value.getClass());
            if (other != null) {
               other.serialize(value, jg, provider);
            }
         } else {
            serializer.serialize(value, jg, provider);
         }
      } else if (defaultValues) {
         Object value = feature.getDefaultValue();

         if (value != null) {
            jg.writeFieldName(getFieldName());
            serializer.serialize(value, jg, provider);
         }
      }
   }

   @Override
   public EObject deserialize(final JsonParser jp, final DeserializationContext ctxt) throws IOException {
      return null;
   }
}
