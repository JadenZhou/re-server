package property;

import io.javalin.http.Context;

import java.util.List;
import java.util.Optional;

public class PropertyController {

    private final PropertyDAO properties;

    public PropertyController(PropertyDAO properties) {
        this.properties = properties;
    }

    // implements POST /property
    public void createProperty(Context ctx) {

        // Extract Property from request body
        // TO DO override Validator exception method to report better error message
        Property property = ctx.bodyValidator(Property.class)
                                .get();

        // store new property in data set
        if (properties.newProperty(property)) {
            ctx.result("Property Created");
            ctx.status(201);
        } else {
            ctx.result("Failed to add property");
            ctx.status(400);
        }
    }

    // implements GET /property
    // Optional query params: minPrice, maxPrice
    // Example: GET http://localhost:7070/property?minPrice=1000000&maxPrice=3000000
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

        if (allProperties.isEmpty()) {
            ctx.html(errorHtml("No Properties Found"));
            ctx.status(404);
        } else {
            ctx.html(propertyListHtml("All Properties", allProperties));
            ctx.status(200);
        }
    }

    // implements GET /property/{propertyID}
    public void getPropertyByID(Context ctx, String id) {
        Optional<Property> property = properties.getPropertyById(id);
        if (property.isPresent()) {
            ctx.html(propertyListHtml("Property " + id, List.of(property.get())));
            ctx.status(200);
        } else {
            ctx.html(errorHtml("Property not found"));
            ctx.status(404);
        }
    }

    // implements GET /property/postcode/{postcodeID}
    public void findPropertyByPostCode(Context ctx, String postCode) {
        List<Property> result = properties.getPropertiesByPostCode(postCode);
        if (result.isEmpty()) {
            ctx.html(errorHtml("No properties for postcode found"));
            ctx.status(404);
        } else {
            ctx.html(propertyListHtml("Properties in Postcode " + postCode, result));
            ctx.status(200);
        }
    }

    private String propertyListHtml(String title, List<Property> props) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>").append(title).append("</title></head><body>");
        sb.append("<h1>").append(title).append("</h1>");
        sb.append("<table border=\"1\" cellpadding=\"6\" cellspacing=\"0\">");
        sb.append("<tr><th>Property ID</th><th>Postcode</th><th>Price</th><th>For Sale</th></tr>");
        for (Property p : props) {
            sb.append("<tr>")
              .append("<td>").append(p.propertyID).append("</td>")
              .append("<td>").append(p.postcode).append("</td>")
              .append("<td>").append(p.propertyPrice).append("</td>")
              .append("<td>").append(p.forSale).append("</td>")
              .append("</tr>");
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private String errorHtml(String message) {
        return "<!DOCTYPE html><html><head><title>Error</title></head><body>"
             + "<h1>Error</h1><p>" + message + "</p></body></html>";
    }
}
