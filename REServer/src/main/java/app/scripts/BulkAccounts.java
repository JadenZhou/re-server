package app.scripts;

import com.mongodb.client.MongoClients;
import org.bson.Document;

import java.util.*;

public class BulkAccounts {

  public static void main(String[] args) {
    var db = MongoClients.create("mongodb+srv://db_user:jJN7mcTy5wylRMFb@cs4530-inclass.q2mtbjo.mongodb.net/")
            .getDatabase("nsw_property_data"); // <-- fixed

    var purchasers = db.getCollection("accounts");

    // Pull postcodes from existing accounts (stored as a single string)
    var postcodes = Arrays.asList("2000","2010","2020", "2030");


    System.out.println("Found " + postcodes.size() + " postcodes");

    var random = new Random();
    var docs   = new ArrayList<Document>();
    String[] accountTypes = {"Admin", "Realtor", "Buyer"};

    for (int i = 0; i < 10000; i++) {
      Collections.shuffle(postcodes, random);
      int count = random.nextInt(6); // 0–5
      List<String> interests = new ArrayList<>(postcodes.subList(0, Math.min(count, postcodes.size())));

      docs.add(new Document()
              .append("name",              "Purchaser" + i)
              .append("account_type",      accountTypes[random.nextInt(accountTypes.length)])
              .append("postcode_interest", interests)
              .append("email",             "purchaser" + i + "@email.com")
              .append("password",          "Password" + i + "!")
              .append("createdAt",         new Date()));
    }

    purchasers.insertMany(docs);
    System.out.println("Inserted: " + docs.size());
  }
}