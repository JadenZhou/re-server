package purchase;

import org.bson.types.ObjectId;

import java.util.Date;

public class Purchase {
  private final ObjectId _id;
  private final ObjectId aid;
  private final ObjectId pid;
  private final Date date;

  public Purchase(ObjectId _id, ObjectId aid, ObjectId pid, Date date) {
    this._id = _id;
    this.aid = aid;
    this.pid = pid;
    this.date = date;
  }

}
