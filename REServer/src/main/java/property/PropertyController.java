package property;

import app.Html;
import io.javalin.http.Context;
import io.javalin.openapi.HttpMethod;
import io.javalin.openapi.OpenApi;
import io.javalin.openapi.OpenApiContent;
import io.javalin.openapi.OpenApiParam;
import io.javalin.openapi.OpenApiRequestBody;
import io.javalin.openapi.OpenApiResponse;

import java.util.List;
import java.util.Optional;

public class PropertyController {

    private static final int LIST_HTML_INITIAL = 1024;

    private final PropertyDAO properties;

    public PropertyController(PropertyDAO properties) {
        this.properties = properties;
    }

    @OpenApi(
            path = "/property",
            methods = HttpMethod.POST,
            summary = "Insert a property sale record",
            tags = {"Property"},
            requestBody = @OpenApiRequestBody(content = @OpenApiContent(from = Property.class), required = true),
            responses = {
                    @OpenApiResponse(status = "201", description = "Created"),
                    @OpenApiResponse(status = "400", description = "Invalid payload")
            })
    public void createProperty(Context ctx) {
        // Extract Property from request body
        // TO DO override Validator exception method to report better error message
        Property property = ctx.bodyValidator(Property.class).get();

        if (properties.newProperty(property)) {
            ctx.result("Property Created");
            ctx.status(201);
        } else {
            ctx.result("Failed to add property");
            ctx.status(400);
        }
    }

    @OpenApi(
            path = "/property",
            methods = HttpMethod.GET,
            summary = "List properties (capped); optionally filter by purchase-price range",
            tags = {"Property"},
            queryParams = {
                    @OpenApiParam(name = "minPrice", type = Long.class, description = "Inclusive lower bound"),
                    @OpenApiParam(name = "maxPrice", type = Long.class, description = "Inclusive upper bound")
            },
            responses = {
                    @OpenApiResponse(status = "200", description = "HTML table of properties"),
                    @OpenApiResponse(status = "404", description = "No properties found")
            })
    public void getAllProperties(Context ctx) {
        String minParam = ctx.queryParam("minPrice");
        String maxParam = ctx.queryParam("maxPrice");

        List<Property> allProperties;
        if (minParam != null || maxParam != null) {
            long min = minParam != null ? Long.parseLong(minParam) : Long.MIN_VALUE;
            long max = maxParam != null ? Long.parseLong(maxParam) : Long.MAX_VALUE;
            allProperties = properties.getPropertiesByPriceRange(min, max);
        } else {
            allProperties = properties.getAllProperties();
        }

        renderListOr404(ctx, "All Properties", "No Properties Found", allProperties);
    }

    @OpenApi(
            path = "/property/{propertyID}",
            methods = HttpMethod.GET,
            summary = "Get the latest sale record for a property ID",
            tags = {"Property"},
            pathParams = @OpenApiParam(name = "propertyID", required = true),
            responses = {
                    @OpenApiResponse(status = "200", description = "Property found"),
                    @OpenApiResponse(status = "404", description = "Property not found")
            })
    public void getPropertyByID(Context ctx, String id) {
        Optional<Property> property = properties.getPropertyById(id);
        if (property.isPresent()) {
            ctx.html(propertyListHtml("Property " + id, List.of(property.get())));
            ctx.status(200);
        } else {
            ctx.html(Html.errorPage("Error", "Property not found"));
            ctx.status(404);
        }
    }

    @OpenApi(
            path = "/property/postcode/{postcode}",
            methods = HttpMethod.GET,
            summary = "List properties in a given NSW postcode",
            tags = {"Property"},
            pathParams = @OpenApiParam(name = "postcode", required = true),
            responses = {
                    @OpenApiResponse(status = "200", description = "Properties found"),
                    @OpenApiResponse(status = "404", description = "No properties in postcode")
            })
    public void findPropertyByPostCode(Context ctx, String postCode) {
        List<Property> result = properties.getPropertiesByPostCode(postCode);
        renderListOr404(ctx,
                "Properties in Postcode " + postCode,
                "No properties for postcode found",
                result);
    }

    private static void renderListOr404(Context ctx, String title, String emptyMsg, List<Property> list) {
        if (list.isEmpty()) {
            ctx.html(Html.errorPage("Error", emptyMsg));
            ctx.status(404);
        } else {
            ctx.html(propertyListHtml(title, list));
            ctx.status(200);
        }
    }

    private static String propertyListHtml(String title, List<Property> props) {
        StringBuilder sb = new StringBuilder(LIST_HTML_INITIAL);
        sb.append("<!DOCTYPE html><html><head><title>").append(Html.escape(title))
          .append("</title></head><body><h1>").append(Html.escape(title)).append("</h1>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">")
          .append("<tr><th>Property ID</th><th>Postcode</th><th>Address</th><th>Council</th>")
          .append("<th>Type</th><th>Last Sale</th><th>Price</th><th>For Sale</th></tr>");
        for (Property p : props) {
            appendRow(sb, p);
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static void appendRow(StringBuilder sb, Property p) {
        sb.append("<tr>")
          .append("<td>").append(safe(p.propertyID)).append("</td>")
          .append("<td>").append(safe(p.postcode)).append("</td>")
          .append("<td>").append(safe(p.address)).append("</td>")
          .append("<td>").append(safe(p.councilName)).append("</td>")
          .append("<td>").append(safe(p.propertyType)).append("</td>")
          .append("<td>").append(safe(p.contractDate)).append("</td>")
          .append("<td>").append(safe(p.propertyPrice)).append("</td>")
          .append("<td>").append(p.forSale).append("</td>")
          .append("</tr>");
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
