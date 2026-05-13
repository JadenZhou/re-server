# User Stories

Documents the requirements implemented so far in the Real Estate Server,
followed by three proposed new requirements.

Format: **As a `<role>`, I want `<feature>` so that `<benefit>`.**

---

## Implemented

### Property records (Exercise 1)

1. **As a buyer**, I want to look up a property by its ID so that I can see
   the property's recorded sale information.
2. **As a buyer**, I want to browse properties in a given NSW postcode so
   that I can survey what has sold in the area I'm considering.
3. **As an analyst**, I want to filter properties by a purchase-price range
   so that I can study market segments.
4. **As a data entry user**, I want to record a new property sale via the
   API so that the dataset stays current as new transactions complete.

### Listings (Exercise 2)

5. **As a property owner**, I want to put one of my existing properties up
   for sale so that buyers can discover it as an active listing.
6. **As a property owner**, I want to update my asking price over time so
   that the listing stays competitive if it isn't selling.
7. **As a buyer**, I want to see the full price history of a listing so
   that I can judge how the seller's expectations have moved.
8. **As an analyst**, I want listings to be flagged when discounted so
   that I can spot motivated sellers without scanning every price entry.
9. **As an operator**, I want to seed 1000 synthetic listings at +20% over
   last sale so that the system has realistic load for development.

### Purchasers — Buyer accounts (Exercise 2)

10. **As a buyer**, I want to register an account with my name and email
    so that the system can remember me.
11. **As a buyer**, I want to register interest in up to five NSW postcodes
    so that the system can later match me to relevant listings.
12. **As a buyer**, I want to add or remove postcodes from my watch list
    individually so that I can refine my search without re-submitting the
    whole list.
13. **As an internal service**, I want to look up all buyers interested in
    a postcode so that I can build matching/notification features on top.
14. **As an operator**, I want to seed a large batch of synthetic buyers
    each with random postcode interests so that the system has realistic
    load for development.

---

## Proposed (new requirements)

### 1. Listing alerts for interested buyers

**As a buyer**, I want to be notified when a new listing appears in one of
my watched postcodes so that I can act on fresh inventory without polling.

*Why it's easy to build:* buyers in the `accounts` collection already carry
their watched postcodes as an embedded array, indexable on `postcodes`.
Listings reference a property whose `post_code` is on the property doc. On
listing creation, a single query against `accounts` by postcode yields the
recipient list. Only the notification channel (email/webhook) is new.

### 2. Price-drop watchlist

**As a buyer**, I want to subscribe to a specific listing and be alerted
when its price drops so that I can move quickly on a property I'm hesitant
about.

*Why it's easy to build:* price updates already flow through a single
endpoint (`POST /listing/{id}/price`) and land in `property_pricing_updates`.
Adding a small `listing_watchers` collection (`{aid, listing_id}`) and
emitting an event whenever a new pricing entry is below the previous latest
is contained. The `is_discounted` flag on the listing already captures the
"currently below entry price" subset of this story for free.

### 3. Postcode market summary

**As an analyst**, I want a per-postcode dashboard of average historical
sale price, count of active listings, and current median asking price so
that I can spot trends without scraping individual records.

*Why it's easy to build:* `properties.post_code` and `listings.pid →
properties._id` are both indexed; one MongoDB aggregation pipeline
(`$lookup` from listings into properties, then `$group` by `post_code`,
joining latest entries from `property_pricing_updates`) produces the
dashboard data in a single query. Results can be cached for read-heavy use.
