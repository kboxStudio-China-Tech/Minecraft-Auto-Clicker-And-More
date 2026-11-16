package mcac.client.net;

/** 一次 tick 的快照（桥帧解析结果）。 */
public final class Snapshot {

    public long seq;
    public double px;
    public double py;
    public double pz;
    public double ph;
    public double yaw;
    public double pitch;
    public boolean edge;
    public double vx;
    public double vz;
    public boolean ground;
    /** 手持物品：0=空手/其他，1=方块，2=剑。 */
    public int hold;

    public int count;
    public final double[] ex = new double[48];
    public final double[] ey = new double[48];
    public final double[] ez = new double[48];
    public final double[] eh = new double[48];
    /** 该实体是否为其他玩家（用于受击跳跃的伤害来源判定）。 */
    public final boolean[] ePlayer = new boolean[48];
}
