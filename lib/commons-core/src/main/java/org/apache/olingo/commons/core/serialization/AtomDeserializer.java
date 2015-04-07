/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.olingo.commons.core.serialization;

import java.io.InputStream;
import java.net.URI;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import org.apache.commons.lang3.StringUtils;
import org.apache.olingo.commons.api.Constants;
import org.apache.olingo.commons.api.data.AbstractODataObject;
import org.apache.olingo.commons.api.data.Annotation;
import org.apache.olingo.commons.api.data.ComplexValue;
import org.apache.olingo.commons.api.data.DeletedEntity;
import org.apache.olingo.commons.api.data.DeletedEntity.Reason;
import org.apache.olingo.commons.api.data.Delta;
import org.apache.olingo.commons.api.data.DeltaLink;
import org.apache.olingo.commons.api.data.Entity;
import org.apache.olingo.commons.api.data.EntitySet;
import org.apache.olingo.commons.api.data.Link;
import org.apache.olingo.commons.api.data.Property;
import org.apache.olingo.commons.api.data.ResWrap;
import org.apache.olingo.commons.api.data.Valuable;
import org.apache.olingo.commons.api.data.ValueType;
import org.apache.olingo.commons.api.domain.ODataError;
import org.apache.olingo.commons.api.domain.ODataOperation;
import org.apache.olingo.commons.api.domain.ODataPropertyType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveType;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeException;
import org.apache.olingo.commons.api.edm.EdmPrimitiveTypeKind;
import org.apache.olingo.commons.api.edm.geo.Geospatial;
import org.apache.olingo.commons.api.format.ContentType;
import org.apache.olingo.commons.api.serialization.ODataDeserializer;
import org.apache.olingo.commons.api.serialization.ODataDeserializerException;
import org.apache.olingo.commons.core.edm.provider.EdmTypeInfo;

import com.fasterxml.aalto.stax.InputFactoryImpl;

public class AtomDeserializer extends AbstractAtomDealer implements ODataDeserializer {

  protected static final XMLInputFactory FACTORY = new InputFactoryImpl();

  private final AtomGeoValueDeserializer geoDeserializer;

  protected XMLEventReader getReader(final InputStream input) throws XMLStreamException {
    return FACTORY.createXMLEventReader(input);
  }

  public AtomDeserializer() {
    geoDeserializer = new AtomGeoValueDeserializer();
  }

  private Object fromPrimitive(final XMLEventReader reader, final StartElement start,
      final EdmTypeInfo typeInfo) throws XMLStreamException, EdmPrimitiveTypeException {

    Object value = null;

    boolean foundEndProperty = false;
    while (reader.hasNext() && !foundEndProperty) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement() && typeInfo != null && typeInfo.getPrimitiveTypeKind().isGeospatial()) {
        final EdmPrimitiveTypeKind geoType =
            EdmPrimitiveTypeKind.valueOfFQN(typeInfo.getFullQualifiedName().toString());
        value = geoDeserializer.deserialize(reader, event.asStartElement(), geoType);
      }

      if (event.isCharacters() && !event.asCharacters().isWhiteSpace()
          && (typeInfo == null || !typeInfo.getPrimitiveTypeKind().isGeospatial())) {

        final String stringValue = event.asCharacters().getData();
        value = typeInfo == null ? stringValue : // TODO: add facets
            ((EdmPrimitiveType) typeInfo.getType()).valueOfString(stringValue, true, null,
                Constants.DEFAULT_PRECISION, Constants.DEFAULT_SCALE, true,
                ((EdmPrimitiveType) typeInfo.getType()).getDefaultType());
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndProperty = true;
      }
    }

    return value;
  }

  private Object fromComplexOrEnum(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {

    Object value = null;

    boolean foundEndProperty = false;
    while (reader.hasNext() && !foundEndProperty) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement()) {
        if (value == null) {
          value = new ComplexValue();
        }

        if (Constants.QNAME_ATOM_ELEM_LINK.equals(event.asStartElement().getName())) {
          final Link link = new Link();
          final Attribute rel = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_REL));
          if (rel != null) {
            link.setRel(rel.getValue());
          }
          final Attribute title = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TITLE));
          if (title != null) {
            link.setTitle(title.getValue());
          }
          final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
          if (href != null) {
            link.setHref(href.getValue());
          }
          final Attribute type = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TYPE));
          if (type != null) {
            link.setType(type.getValue());
          }

          if (link.getRel().startsWith(Constants.NS_NAVIGATION_LINK_REL)) {

            ((ComplexValue) value).getNavigationLinks().add(link);
            inline(reader, event.asStartElement(), link);
          } else if (link.getRel().startsWith(Constants.NS_ASSOCIATION_LINK_REL)) {

            ((Valuable) value).asComplex().getAssociationLinks().add(link);
          }
        } else {
          ((ComplexValue) value).getValue().add(property(reader, event.asStartElement()));
        }
      }

      if (event.isCharacters() && !event.asCharacters().isWhiteSpace()) {
        value = event.asCharacters().getData();
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndProperty = true;
      }
    }

    return value;
  }

  private void fromCollection(final Valuable valuable, final XMLEventReader reader, final StartElement start,
      final EdmTypeInfo typeInfo) throws XMLStreamException, EdmPrimitiveTypeException {

    List<Object> values = new ArrayList<Object>();
    ValueType valueType = ValueType.COLLECTION_PRIMITIVE;

    final EdmTypeInfo type = typeInfo == null ? null :
        new EdmTypeInfo.Builder().setTypeExpression(typeInfo.getFullQualifiedName().toString()).build();

    boolean foundEndProperty = false;
    while (reader.hasNext() && !foundEndProperty) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement()) {
        switch (guessPropertyType(reader, typeInfo)) {
        case COMPLEX:
          final Object complexValue = fromComplexOrEnum(reader, event.asStartElement());
          valueType = ValueType.COLLECTION_COMPLEX;
          values.add(complexValue);
          break;

        case ENUM:
          valueType = ValueType.COLLECTION_ENUM;
          values.add(fromComplexOrEnum(reader, event.asStartElement()));
          break;

        case PRIMITIVE:
          final Object value = fromPrimitive(reader, event.asStartElement(), type);
          valueType = value instanceof Geospatial ?
              ValueType.COLLECTION_GEOSPATIAL : ValueType.COLLECTION_PRIMITIVE;
          values.add(value);
          break;

        default:
          // do not add null or empty values
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndProperty = true;
      }
    }
    valuable.setValue(valueType, values);
  }

  private ODataPropertyType guessPropertyType(final XMLEventReader reader, final EdmTypeInfo typeInfo)
      throws XMLStreamException {

    XMLEvent child = null;
    while (reader.hasNext() && child == null) {
      final XMLEvent event = reader.peek();
      if (event.isCharacters() && event.asCharacters().isWhiteSpace()) {
        reader.nextEvent();
      } else {
        child = event;
      }
    }

    final ODataPropertyType type;
    if (child == null) {
      type = typeInfo == null || typeInfo.isPrimitiveType() ? ODataPropertyType.PRIMITIVE : ODataPropertyType.ENUM;
    } else {
      if (child.isStartElement()) {
        if (Constants.NS_GML.equals(child.asStartElement().getName().getNamespaceURI())) {
          type = ODataPropertyType.PRIMITIVE;
        } else if (elementQName.equals(child.asStartElement().getName())) {
          type = ODataPropertyType.COLLECTION;
        } else {
          type = ODataPropertyType.COMPLEX;
        }
      } else if (child.isCharacters()) {
        type = typeInfo == null || typeInfo.isPrimitiveType()
            ? ODataPropertyType.PRIMITIVE
            : ODataPropertyType.ENUM;
      } else {
        type = ODataPropertyType.EMPTY;
      }
    }

    return type;
  }

  private Property property(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {

    final Property property = new Property();

    if (propertyValueQName.equals(start.getName())) {
      // retrieve name from context
      final Attribute context = start.getAttributeByName(contextQName);
      if (context != null) {
        property.setName(StringUtils.substringAfterLast(context.getValue(), "/"));
      }
    } else {
      property.setName(start.getName().getLocalPart());
    }

    valuable(property, reader, start);

    return property;
  }

  private void valuable(final Valuable valuable, final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {

    final Attribute nullAttr = start.getAttributeByName(nullQName);

    final Attribute typeAttr = start.getAttributeByName(typeQName);
    final String typeAttrValue = typeAttr == null ? null : typeAttr.getValue();

    final EdmTypeInfo typeInfo = StringUtils.isBlank(typeAttrValue) ? null :
        new EdmTypeInfo.Builder().setTypeExpression(typeAttrValue).build();

    if (typeInfo != null) {
      valuable.setType(typeInfo.internal());
    }

    final ODataPropertyType propType = typeInfo == null ? guessPropertyType(reader, typeInfo) :
        typeInfo.isCollection() ? ODataPropertyType.COLLECTION :
            typeInfo.isPrimitiveType() ? ODataPropertyType.PRIMITIVE : ODataPropertyType.COMPLEX;

    if (nullAttr == null) {
      switch (propType) {
      case COLLECTION:
        fromCollection(valuable, reader, start, typeInfo);
        break;

      case COMPLEX:
        final Object complexValue = fromComplexOrEnum(reader, start);
        valuable.setValue(complexValue instanceof ComplexValue ? ValueType.COMPLEX : ValueType.ENUM,
            complexValue);
        break;

      case PRIMITIVE:
        // No type specified? Defaults to Edm.String
        if (typeInfo == null) {
          valuable.setType(EdmPrimitiveTypeKind.String.getFullQualifiedName().toString());
        }
        final Object value = fromPrimitive(reader, start, typeInfo);
        valuable.setValue(value instanceof Geospatial ? ValueType.GEOSPATIAL : ValueType.PRIMITIVE, value);
        break;

      case EMPTY:
      default:
        valuable.setValue(ValueType.PRIMITIVE, StringUtils.EMPTY);
      }
    } else {
      valuable.setValue(propType == ODataPropertyType.PRIMITIVE ? ValueType.PRIMITIVE :
          propType == ODataPropertyType.ENUM ? ValueType.ENUM :
              propType == ODataPropertyType.COMPLEX ? ValueType.COMPLEX :
                  propType == ODataPropertyType.COLLECTION ? ValueType.COLLECTION_PRIMITIVE : ValueType.PRIMITIVE,
          null);
    }
  }

  @Override
  public ResWrap<Property> toProperty(final InputStream input) throws ODataDeserializerException {
    try {
      final XMLEventReader reader = getReader(input);
      final StartElement start = skipBeforeFirstStartElement(reader);
      return getContainer(start, property(reader, start));
    } catch (XMLStreamException e) {
      throw new ODataDeserializerException(e);
    } catch (final EdmPrimitiveTypeException e) {
      throw new ODataDeserializerException(e);
    }
  }

  private StartElement skipBeforeFirstStartElement(final XMLEventReader reader) throws XMLStreamException {
    StartElement startEvent = null;
    while (reader.hasNext() && startEvent == null) {
      final XMLEvent event = reader.nextEvent();
      if (event.isStartElement()) {
        startEvent = event.asStartElement();
      }
    }
    if (startEvent == null) {
      throw new IllegalArgumentException("Cannot find any XML start element");
    }

    return startEvent;
  }

  private void common(final XMLEventReader reader, final StartElement start,
      final AbstractODataObject object, final String key) throws XMLStreamException {

    boolean foundEndElement = false;
    while (reader.hasNext() && !foundEndElement) {
      final XMLEvent event = reader.nextEvent();

      if (event.isCharacters() && !event.asCharacters().isWhiteSpace()) {
        try {
          object.setCommonProperty(key, event.asCharacters().getData());
        } catch (ParseException e) {
          throw new XMLStreamException("While parsing Atom entry or feed common elements", e);
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndElement = true;
      }
    }
  }

  private void inline(final XMLEventReader reader, final StartElement start, final Link link)
      throws XMLStreamException, EdmPrimitiveTypeException {

    boolean foundEndElement = false;
    while (reader.hasNext() && !foundEndElement) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement()) {
        if (inlineQName.equals(event.asStartElement().getName())) {
          StartElement inline = getStartElement(reader);
          if (inline != null) {
            if (Constants.QNAME_ATOM_ELEM_ENTRY.equals(inline.getName())) {
              link.setInlineEntity(entity(reader, inline));
            }
            if (Constants.QNAME_ATOM_ELEM_FEED.equals(inline.getName())) {
              link.setInlineEntitySet(entitySet(reader, inline));
            }
          }
        } else if (annotationQName.equals(event.asStartElement().getName())) {
          link.getAnnotations().add(annotation(reader, event.asStartElement()));
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndElement = true;
      }
    }
  }

  private StartElement getStartElement(XMLEventReader reader) throws XMLStreamException {
    while (reader.hasNext()) {
      final XMLEvent innerEvent = reader.peek();
      if (innerEvent.isCharacters() && innerEvent.asCharacters().isWhiteSpace()) {
        reader.nextEvent();
      } else if (innerEvent.isStartElement()) {
        return innerEvent.asStartElement();
      } else if (innerEvent.isEndElement() && inlineQName.equals(innerEvent.asEndElement().getName())) {
        return null;
      }
    }
    return null;
  }

  public ResWrap<Delta> delta(final InputStream input)
      throws XMLStreamException, EdmPrimitiveTypeException {
    final XMLEventReader reader = getReader(input);
    final StartElement start = skipBeforeFirstStartElement(reader);
    return getContainer(start, delta(reader, start));
  }

  private Delta delta(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {
    if (!Constants.QNAME_ATOM_ELEM_FEED.equals(start.getName())) {
      return null;
    }
    final Delta delta = new Delta();
    final Attribute xmlBase = start.getAttributeByName(Constants.QNAME_ATTR_XML_BASE);
    if (xmlBase != null) {
      delta.setBaseURI(xmlBase.getValue());
    }

    boolean foundEndFeed = false;
    while (reader.hasNext() && !foundEndFeed) {
      final XMLEvent event = reader.nextEvent();
      if (event.isStartElement()) {
        if (countQName.equals(event.asStartElement().getName())) {
          count(reader, event.asStartElement(), delta);
        } else if (Constants.QNAME_ATOM_ELEM_ID.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), delta, "id");
        } else if (Constants.QNAME_ATOM_ELEM_TITLE.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), delta, "title");
        } else if (Constants.QNAME_ATOM_ELEM_SUMMARY.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), delta, "summary");
        } else if (Constants.QNAME_ATOM_ELEM_UPDATED.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), delta, "updated");
        } else if (Constants.QNAME_ATOM_ELEM_LINK.equals(event.asStartElement().getName())) {
          final Attribute rel = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_REL));
          if (rel != null) {
            if (Constants.NEXT_LINK_REL.equals(rel.getValue())) {
              final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
              if (href != null) {
                delta.setNext(URI.create(href.getValue()));
              }
            }
            if (Constants.NS_DELTA_LINK_REL.equals(rel.getValue())) {
              final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
              if (href != null) {
                delta.setDeltaLink(URI.create(href.getValue()));
              }
            }
          }
        } else if (Constants.QNAME_ATOM_ELEM_ENTRY.equals(event.asStartElement().getName())) {
          delta.getEntities().add(entity(reader, event.asStartElement()));
        } else if (deletedEntryQName.equals(event.asStartElement().getName())) {
          final DeletedEntity deletedEntity = new DeletedEntity();

          final Attribute ref = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_REF));
          if (ref != null) {
            deletedEntity.setId(URI.create(ref.getValue()));
          }
          final Attribute reason = event.asStartElement().getAttributeByName(reasonQName);
          if (reason != null) {
            deletedEntity.setReason(Reason.valueOf(reason.getValue()));
          }

          delta.getDeletedEntities().add(deletedEntity);
        } else if (linkQName.equals(event.asStartElement().getName())
            || deletedLinkQName.equals(event.asStartElement().getName())) {

          final DeltaLink link = new DeltaLink();

          final Attribute source = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_SOURCE));
          if (source != null) {
            link.setSource(URI.create(source.getValue()));
          }
          final Attribute relationship =
              event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_RELATIONSHIP));
          if (relationship != null) {
            link.setRelationship(relationship.getValue());
          }
          final Attribute target = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TARGET));
          if (target != null) {
            link.setTarget(URI.create(target.getValue()));
          }

          if (linkQName.equals(event.asStartElement().getName())) {
            delta.getAddedLinks().add(link);
          } else {
            delta.getDeletedLinks().add(link);
          }
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndFeed = true;
      }
    }

    return delta;
  }

  private void properties(final XMLEventReader reader, final StartElement start, final Entity entity)
      throws XMLStreamException, EdmPrimitiveTypeException {

    final Map<String, List<Annotation>> annotations = new HashMap<String, List<Annotation>>();

    boolean foundEndProperties = false;
    while (reader.hasNext() && !foundEndProperties) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement()) {
        if (annotationQName.equals(event.asStartElement().getName())) {
          final String target = event.asStartElement().
              getAttributeByName(QName.valueOf(Constants.ATTR_TARGET)).getValue();
          if (!annotations.containsKey(target)) {
            annotations.put(target, new ArrayList<Annotation>());
          }
          annotations.get(target).add(annotation(reader, event.asStartElement()));
        } else {
          entity.getProperties().add(property(reader, event.asStartElement()));
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndProperties = true;
      }
    }

    for (Property property : entity.getProperties()) {
      if (annotations.containsKey(property.getName())) {
        property.getAnnotations().addAll(annotations.get(property.getName()));
      }
    }
  }

  private Annotation annotation(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {

    final Annotation annotation = new Annotation();

    annotation.setTerm(start.getAttributeByName(QName.valueOf(Constants.ATOM_ATTR_TERM)).getValue());
    valuable(annotation, reader, start);

    return annotation;
  }

  private Entity entityRef(final StartElement start) throws XMLStreamException {
    final Entity entity = new Entity();

    final Attribute entityRefId = start.getAttributeByName(Constants.QNAME_ATOM_ATTR_ID);
    if (entityRefId != null) {
      entity.setId(URI.create(entityRefId.getValue()));
    }

    return entity;
  }

  private Entity entity(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {
    final Entity entity;
    if (entryRefQName.equals(start.getName())) {
      entity = entityRef(start);
    } else if (Constants.QNAME_ATOM_ELEM_ENTRY.equals(start.getName())) {
      entity = new Entity();
      final Attribute xmlBase = start.getAttributeByName(Constants.QNAME_ATTR_XML_BASE);
      if (xmlBase != null) {
        entity.setBaseURI(xmlBase.getValue());
      }

      final Attribute etag = start.getAttributeByName(etagQName);
      if (etag != null) {
        entity.setETag(etag.getValue());
      }

      boolean foundEndEntry = false;
      while (reader.hasNext() && !foundEndEntry) {
        final XMLEvent event = reader.nextEvent();

        if (event.isStartElement()) {
          if (Constants.QNAME_ATOM_ELEM_ID.equals(event.asStartElement().getName())) {
            common(reader, event.asStartElement(), entity, "id");
          } else if (Constants.QNAME_ATOM_ELEM_TITLE.equals(event.asStartElement().getName())) {
            common(reader, event.asStartElement(), entity, "title");
          } else if (Constants.QNAME_ATOM_ELEM_SUMMARY.equals(event.asStartElement().getName())) {
            common(reader, event.asStartElement(), entity, "summary");
          } else if (Constants.QNAME_ATOM_ELEM_UPDATED.equals(event.asStartElement().getName())) {
            common(reader, event.asStartElement(), entity, "updated");
          } else if (Constants.QNAME_ATOM_ELEM_CATEGORY.equals(event.asStartElement().getName())) {
            final Attribute term = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATOM_ATTR_TERM));
            if (term != null) {
              entity.setType(new EdmTypeInfo.Builder().setTypeExpression(term.getValue()).build().internal());
            }
          } else if (Constants.QNAME_ATOM_ELEM_LINK.equals(event.asStartElement().getName())) {
            final Link link = new Link();
            final Attribute rel = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_REL));
            if (rel != null) {
              link.setRel(rel.getValue());
            }
            final Attribute title = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TITLE));
            if (title != null) {
              link.setTitle(title.getValue());
            }
            final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
            if (href != null) {
              link.setHref(href.getValue());
            }
            final Attribute type = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TYPE));
            if (type != null) {
              link.setType(type.getValue());
            }

            if (Constants.SELF_LINK_REL.equals(link.getRel())) {
              entity.setSelfLink(link);
            } else if (Constants.EDIT_LINK_REL.equals(link.getRel())) {
              entity.setEditLink(link);
            } else if (Constants.EDITMEDIA_LINK_REL.equals(link.getRel())) {
              final Attribute mediaETag = event.asStartElement().getAttributeByName(etagQName);
              if (mediaETag != null) {
                entity.setMediaETag(mediaETag.getValue());
              }
            } else if (link.getRel().startsWith(Constants.NS_NAVIGATION_LINK_REL)) {

              entity.getNavigationLinks().add(link);
              inline(reader, event.asStartElement(), link);
            } else if (link.getRel().startsWith(Constants.NS_ASSOCIATION_LINK_REL)) {

              entity.getAssociationLinks().add(link);
            } else if (link.getRel().startsWith(Constants.NS_MEDIA_EDIT_LINK_REL)) {

              final Attribute metag = event.asStartElement().getAttributeByName(etagQName);
              if (metag != null) {
                link.setMediaETag(metag.getValue());
              }
              entity.getMediaEditLinks().add(link);
            }
          } else if (actionQName.equals(event.asStartElement().getName())) {
            final ODataOperation operation = new ODataOperation();
            final Attribute metadata =
                event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_METADATA));
            if (metadata != null) {
              operation.setMetadataAnchor(metadata.getValue());
            }
            final Attribute title = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TITLE));
            if (title != null) {
              operation.setTitle(title.getValue());
            }
            final Attribute target = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TARGET));
            if (target != null) {
              operation.setTarget(URI.create(target.getValue()));
            }

            entity.getOperations().add(operation);
          } else if (Constants.QNAME_ATOM_ELEM_CONTENT.equals(event.asStartElement().getName())) {
            final Attribute type = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_TYPE));
            if (type == null || ContentType.APPLICATION_XML.toContentTypeString().equals(type.getValue())) {
              properties(reader, skipBeforeFirstStartElement(reader), entity);
            } else {
              entity.setMediaContentType(type.getValue());
              final Attribute src = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATOM_ATTR_SRC));
              if (src != null) {
                entity.setMediaContentSource(URI.create(src.getValue()));
              }
            }
          } else if (propertiesQName.equals(event.asStartElement().getName())) {
            properties(reader, event.asStartElement(), entity);
          } else if (annotationQName.equals(event.asStartElement().getName())) {
            entity.getAnnotations().add(annotation(reader, event.asStartElement()));
          }
        }

        if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
          foundEndEntry = true;
        }
      }
    } else {
      entity = null;
    }

    return entity;
  }

  @Override
  public ResWrap<Entity> toEntity(final InputStream input) throws ODataDeserializerException {
    try {
      final XMLEventReader reader = getReader(input);
      final StartElement start = skipBeforeFirstStartElement(reader);
      final Entity entity = entity(reader, start);
      if (entity == null) {
        throw new ODataDeserializerException("No entity found!");
      } else {
        return getContainer(start, entity);
      }
    } catch (XMLStreamException e) {
      throw new ODataDeserializerException(e);
    } catch (final EdmPrimitiveTypeException e) {
      throw new ODataDeserializerException(e);
    }
  }

  private void count(final XMLEventReader reader, final StartElement start, final EntitySet entitySet)
      throws XMLStreamException {

    boolean foundEndElement = false;
    while (reader.hasNext() && !foundEndElement) {
      final XMLEvent event = reader.nextEvent();

      if (event.isCharacters() && !event.asCharacters().isWhiteSpace()) {
        entitySet.setCount(Integer.valueOf(event.asCharacters().getData()));
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndElement = true;
      }
    }
  }

  private EntitySet entitySet(final XMLEventReader reader, final StartElement start)
      throws XMLStreamException, EdmPrimitiveTypeException {
    if (!Constants.QNAME_ATOM_ELEM_FEED.equals(start.getName())) {
      return null;
    }
    final EntitySet entitySet = new EntitySet();
    final Attribute xmlBase = start.getAttributeByName(Constants.QNAME_ATTR_XML_BASE);
    if (xmlBase != null) {
      entitySet.setBaseURI(xmlBase.getValue());
    }

    boolean foundEndFeed = false;
    while (reader.hasNext() && !foundEndFeed) {
      final XMLEvent event = reader.nextEvent();
      if (event.isStartElement()) {
        if (countQName.equals(event.asStartElement().getName())) {
          count(reader, event.asStartElement(), entitySet);
        } else if (Constants.QNAME_ATOM_ELEM_ID.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), entitySet, "id");
        } else if (Constants.QNAME_ATOM_ELEM_TITLE.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), entitySet, "title");
        } else if (Constants.QNAME_ATOM_ELEM_SUMMARY.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), entitySet, "summary");
        } else if (Constants.QNAME_ATOM_ELEM_UPDATED.equals(event.asStartElement().getName())) {
          common(reader, event.asStartElement(), entitySet, "updated");
        } else if (Constants.QNAME_ATOM_ELEM_LINK.equals(event.asStartElement().getName())) {
          final Attribute rel = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_REL));
          if (rel != null) {
            if (Constants.NEXT_LINK_REL.equals(rel.getValue())) {
              final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
              if (href != null) {
                entitySet.setNext(URI.create(href.getValue()));
              }
            }
            if (Constants.NS_DELTA_LINK_REL.equals(rel.getValue())) {
              final Attribute href = event.asStartElement().getAttributeByName(QName.valueOf(Constants.ATTR_HREF));
              if (href != null) {
                entitySet.setDeltaLink(URI.create(href.getValue()));
              }
            }
          }
        } else if (Constants.QNAME_ATOM_ELEM_ENTRY.equals(event.asStartElement().getName())) {
          entitySet.getEntities().add(entity(reader, event.asStartElement()));
        } else if (entryRefQName.equals(event.asStartElement().getName())) {
          entitySet.getEntities().add(entityRef(event.asStartElement()));
        } else if (annotationQName.equals(event.asStartElement().getName())) {
          entitySet.getAnnotations().add(annotation(reader, event.asStartElement()));
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndFeed = true;
      }
    }

    return entitySet;
  }

  @Override
  public ResWrap<EntitySet> toEntitySet(final InputStream input) throws ODataDeserializerException {
    try {
      final XMLEventReader reader = getReader(input);
      final StartElement start = skipBeforeFirstStartElement(reader);
      return getContainer(start, entitySet(reader, start));
    } catch (XMLStreamException e) {
      throw new ODataDeserializerException(e);
    } catch (final EdmPrimitiveTypeException e) {
      throw new ODataDeserializerException(e);
    }
  }

  private ODataError error(final XMLEventReader reader, final StartElement start) throws XMLStreamException {
    final ODataError error = new ODataError();

    boolean setCode = false;
    boolean codeSet = false;
    boolean setMessage = false;
    boolean messageSet = false;
    boolean setTarget = false;
    boolean targetSet = false;

    boolean foundEndElement = false;
    while (reader.hasNext() && !foundEndElement) {
      final XMLEvent event = reader.nextEvent();

      if (event.isStartElement()) {
        if (errorCodeQName.equals(event.asStartElement().getName())) {
          setCode = true;
        } else if (errorMessageQName.equals(event.asStartElement().getName())) {
          setMessage = true;
        } else if (errorTargetQName.equals(event.asStartElement().getName())) {
          setTarget = true;
        }
      }

      if (event.isCharacters() && !event.asCharacters().isWhiteSpace()) {
        if (setCode && !codeSet) {
          error.setCode(event.asCharacters().getData());
          setCode = false;
          codeSet = true;
        }
        if (setMessage && !messageSet) {
          error.setMessage(event.asCharacters().getData());
          setMessage = false;
          messageSet = true;
        }
        if (setTarget && !targetSet) {
          error.setTarget(event.asCharacters().getData());
          setTarget = false;
          targetSet = true;
        }
      }

      if (event.isEndElement() && start.getName().equals(event.asEndElement().getName())) {
        foundEndElement = true;
      }
    }

    return error;
  }

  @Override
  public ODataError toError(final InputStream input) throws ODataDeserializerException {
    try {
      final XMLEventReader reader = getReader(input);
      final StartElement start = skipBeforeFirstStartElement(reader);
      return error(reader, start);
    } catch (XMLStreamException e) {
      throw new ODataDeserializerException(e);
    }
  }

  private <T> ResWrap<T> getContainer(final StartElement start, final T object) {
    final Attribute context = start.getAttributeByName(contextQName);
    final Attribute metadataETag = start.getAttributeByName(metadataEtagQName);

    return new ResWrap<T>(
        context == null ? null : URI.create(context.getValue()),
        metadataETag == null ? null : metadataETag.getValue(),
        object);
  }
}
