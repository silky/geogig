/* Copyright (c) 2013-2014 Boundless and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/org/documents/edl-v10.html
 *
 * Contributors:
 * Victor Olaya (Boundless) - initial implementation
 */
package org.locationtech.geogig.osm.cli.commands;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import org.geotools.data.DataStore;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.locationtech.geogig.cli.CLICommand;
import org.locationtech.geogig.cli.CommandFailedException;
import org.locationtech.geogig.cli.GeogigCLI;
import org.locationtech.geogig.cli.InvalidParameterException;
import org.locationtech.geogig.cli.annotation.ReadOnly;
import org.locationtech.geogig.geotools.cli.porcelain.AbstractSLCommand;
import org.locationtech.geogig.geotools.plumbing.ExportOp;
import org.locationtech.geogig.geotools.plumbing.GeoToolsOpException;
import org.locationtech.geogig.osm.internal.Mapping;
import org.locationtech.geogig.osm.internal.MappingRule;
import org.opengis.feature.Feature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.GeometryDescriptor;
import org.opengis.filter.Filter;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.vividsolutions.jts.awt.PointShapeFactory.Point;

/**
 * Exports OSM into a SpatiaLite database, using a data mapping
 * 
 * @see ExportOp
 */
@ReadOnly
@Parameters(commandNames = "export-sl", commandDescription = "Export OSM data to a Spatialite database, using a data mapping")
public class OSMExportSL extends AbstractSLCommand implements CLICommand {

    @Parameter(names = { "--overwrite", "-o" }, description = "Overwrite output tables")
    public boolean overwrite;

    @Parameter(names = { "--mapping" }, description = "The file that contains the data mapping to use")
    public String mappingFile;

    /**
     * Executes the export command using the provided options.
     */
    @Override
    protected void runInternal(GeogigCLI cli) throws IOException {
        Preconditions.checkNotNull(mappingFile != null, "A data mapping file must be specified");

        final Mapping mapping = Mapping.fromFile(mappingFile);
        List<MappingRule> rules = mapping.getRules();
        checkParameter(!rules.isEmpty(), "No rules are defined in the specified mapping");

        for (MappingRule rule : rules) {
            exportRule(rule, cli);
        }
    }

    private void exportRule(final MappingRule rule, final GeogigCLI cli) throws IOException {
        Function<Feature, Optional<Feature>> function = new Function<Feature, Optional<Feature>>() {
            @Override
            public Optional<Feature> apply(Feature feature) {
                Optional<Feature> mapped = rule.apply(feature);
                return mapped;
            }
        };

        SimpleFeatureType outputFeatureType = rule.getFeatureType();
        String path = getOriginTreesFromOutputFeatureType(outputFeatureType);
        DataStore dataStore = getDataStore();
        try {
            final String tableName = ensureTableExists(outputFeatureType, dataStore);
            final SimpleFeatureSource source = dataStore.getFeatureSource(tableName);

            if (!(source instanceof SimpleFeatureStore)) {
                throw new CommandFailedException("Could not create feature store.");
            }
            final SimpleFeatureStore store = (SimpleFeatureStore) source;
            if (overwrite) {
                try {
                    store.removeFeatures(Filter.INCLUDE);
                } catch (IOException e) {
                    throw new CommandFailedException("Error truncating table " + tableName, e);
                }
            }
            ExportOp op = cli.getGeogig().command(ExportOp.class).setFeatureStore(store)
                    .setPath(path).setFeatureTypeConversionFunction(function);
            try {
                op.setProgressListener(cli.getProgressListener()).call();
                cli.getConsole().println("OSM data exported successfully to " + tableName);
            } catch (IllegalArgumentException iae) {
                throw new InvalidParameterException(iae.getMessage(), iae);
            } catch (GeoToolsOpException e) {
                throw new CommandFailedException("Could not export. Error:" + e.statusCode.name(),
                        e);
            }
        } finally {
            dataStore.dispose();
        }
    }

    private String ensureTableExists(SimpleFeatureType outputFeatureType, DataStore dataStore)
            throws IOException {
        final String tableName = outputFeatureType.getName().getLocalPart();
        if (Arrays.asList(dataStore.getTypeNames()).contains(tableName)) {
            if (!overwrite) {
                throw new CommandFailedException(
                        "The selected table already exists. Use -o to overwrite");
            }
        } else {
            try {
                dataStore.createSchema(outputFeatureType);
            } catch (IOException e) {
                throw new CommandFailedException("Cannot create new table " + tableName);
            }
        }
        return tableName;
    }

    private String getOriginTreesFromOutputFeatureType(SimpleFeatureType featureType) {
        GeometryDescriptor descriptor = featureType.getGeometryDescriptor();
        Class<?> clazz = descriptor.getType().getBinding();
        if (clazz.equals(Point.class)) {
            return "node";
        } else {
            return "way";
        }
    }
}
