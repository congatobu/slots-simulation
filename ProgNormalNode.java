import daj.Message;
import daj.Program;
import java.util.*;

public class ProgNormalNode extends Program {
    public static final int STS_ACTIVE = 1;
    public static final int STS_RUNNING = 2;
    public static final int STS_WAIT_STATUS = 3;
    public static final int STS_WAIT_INIT = 4;
    public static final int STS_DISCONNECTED = 5;
    public static final int STS_REQ_SLOTS = 6;
    public static final int STS_NEW = 7;
    public static final int STS_MERGE_STATUS = 8;
    
    public static final int FREE_LOW = 4;
    public static final int FREE_HIGH = 8;
    public static final int GIVE_AWAY = 4;

    public static final int NO_PRIMARY_MBR = -1;

    public static final int MIN_OWNED_SLOTS = 8;
    public static final int FREE_SLOTS_LOW = 4;
    public static final int SLOTS_BY_MSG = 1024;

    public static final int MEDIAN_CHANGE_INTERVAL = 5000;

    public static final int LT_UNIT = 23;
    public static final int LT_MIN = 1;
    public static final int LT_MAX = 100;

    public static final int FI_MAX=  10;
    public static final int FI_MIN = 1;
    public static final int FI_RANGE = 4;

    public static final int FI_MIN_AVG = 5;
    public static final int FI_MAX_AVG = 10;
    
    public static final double LAMBDA_MIN = 0.3;
    public static final double LAMBDA_MAX = 0.7;
    
    public static final int RELAMBDA = 10000;

    private final Random random;
    private double lambdaArrival;
    
    int lastTimeRegistered;

    private int arrivalMedian;
    private Slot[] slotsTable = new Slot[SlotsDonation.TOTAL_SLOTS];
    private boolean[] activeNodes = new boolean[SlotsDonation.MAX_NODES+1];
    private boolean[] initializedNodes = new boolean[SlotsDonation.MAX_NODES+1];
    private boolean[] donorsNodes = new boolean[SlotsDonation.MAX_NODES+1];
    private boolean[] bmPendingNodes = new boolean[SlotsDonation.MAX_NODES+1];
    private boolean[] bmWaitSts = new boolean[SlotsDonation.MAX_NODES+1];
    private final int nodeId;
    private int state = STS_DISCONNECTED;
    private boolean gotAtLeastOne = false;
    private int counterForksSucceded = 0;
    private int counterForksFailed = 0;
    private int counterExits = 0;
    private int counterConnects = 0;
    private int counterDisconnects = 0;
    private int counterRequestedSlots = 0;
    private int counterGotSlots = 0;
    private int counterDonatedSlots = 0;
    private int counterGotZeroSlots = 0;
    private int[] counterGotFirstSlotAt = new int[SlotsDonation.MAX_NODES-1];
    private int counterAtMessage = 0;
    private int timeLeftToFork;
    private int nextLambdaChange;
    
    private boolean pendingTake = false;
    private boolean pendingGiveAway = false;

    public ProgNormalNode(int id) {
        this.random = new Random();
        this.nodeId = id;
        for(int i = 0; i < SlotsDonation.TOTAL_SLOTS; i++) {
            slotsTable[i] = new Slot(Slot.NO_OWNER, Slot.STATUS_UNCLAIMED, 0);
        }

        this.cleanNodesLists();
    }

    @Override
    public void main()  {
        int number;
        number = this.nodeId * 1;
        println("Sleeping: "+number);
        sleep(number);
        this.arrivalMedian = this.getNextArrivalMedian();
        this.lambdaArrival = this.getLambdaArrivals();
        this.timeLeftToFork = getTime()+this.getNextDeltaFork();
        this.nextLambdaChange = RELAMBDA;
        println("LAMBDA ARRIVALS: "+this.lambdaArrival);
        
        this.doConnect();

        // Start with algorithm
        this.lastTimeRegistered = getTime();
        this.slotsLoop();
    }
    
    private double getLambdaArrivals(){
        double lambda = this.random.nextDouble();
        while(lambda < LAMBDA_MIN || lambda > LAMBDA_MAX) {
            lambda = this.random.nextDouble();
        }
        return this.round(lambda);
    }
    
    private double round(double val) {
        return((double) Math.round(val * 100) / 100);
    }

    public void getInfoLine() {
        //(NodeId, Forks OK, Forks Failed, Exits, TotalRequested, TotalReceived, GotZero)
        System.out.println(""+this.nodeId+','+this.counterForksSucceded+','+
                this.counterForksFailed+','+this.counterExits+','+this.counterRequestedSlots+
                ','+this.counterGotSlots+','+this.counterGotZeroSlots);
    }

  
    private void bulkInitTo(int last) {
        for(int i = 1; i <= last; i++) {
            this.initializedNodes[i] = true;
            this.activeNodes[i] = true;
        }
    }

    /*===========================================================================*
     *				sp_join											 *
     * A NEW member has joint the VM group but it is not initialized
     *===========================================================================*/
    private void handleSpreadJoin(SpreadMessageJoin msg) {
        this.println("Handling Spread Join. "
                + "Will mark as initialized up to node #"+msg.senderId);
        this.bulkInitTo(msg.senderId);
    }

    /****************
     * AUXILIARY
     ****************/

    /**
     * Broadcasting a message is sending it to the spread node
     * @param msg
     */
    public void broadcast(Message msg) {
        out(0).send(msg);
    }

    private void slotsLoop() {
        this.state = STS_RUNNING;
        Message msg;
        while(true) {
            this.println("Waiting for message...");
            msg = this.in(0).receive(1);
            if (msg != null) {
                if(msg instanceof SpreadMessage) {
                    if(msg instanceof SpreadMessageJoin) {
                        this.handleSpreadJoin((SpreadMessageJoin)msg);
                    } else {
                        this.println("THIS SHOULD NOT HAPPEN!!! SpreadMessage non JOIN or LEAVE!");
                    }
                } else if (this.isConnected()) {
/*
                        Contrast Algorithm Handling
*/
                    if (msg instanceof SlotsMessageTakeSlots) {
                        this.handleTakeSlots((SlotsMessageTakeSlots)msg);
                    } else if (msg instanceof SlotsMessageGiveAway) {
                        this.handleGiveAway((SlotsMessageGiveAway)msg);
                    } else {
                        this.println("UNHANDLED SLOT MESSAGE!!!");
                        println(msg.getClass().getSimpleName());
                    }
                } else {
                    this.println("Ignoring Slots Message. Node is not connected!");
                }
            }

            //check fork or exit
            if (this.getInitializedNodes() == SlotsDonation.NODES){
                this.processForkExit();
                this.slotsManagement();
            } else {
                this.println("Not all nodes init. Nodes Init: "+this.getInitializedNodes());
            }
        }
    }
    
    private void handleTakeSlots(SlotsMessageTakeSlots msg) {
        int takenSlots = msg.getQuantity();
        int[] takenArray = new int[takenSlots];
        int i,t;
        
        t = 0;
        
        for (i = 0; i < takenSlots; i++) {
            takenArray[i] = -1;
        }
        
        for (i = 0; i < takenSlots; i++) {
            int freeSlot = this.getFirstFreeHomelessSlotIndex();
            if(freeSlot != -1) {
                this.slotsTable[freeSlot].setOwner(msg.senderId);
                this.slotsTable[freeSlot].setStatus(Slot.STATUS_FREE);
                takenArray[i] = freeSlot;
                t++;
            } else {

                // end loop
                i = takenSlots;
            }
        }
        
        this.println("Handling Take["+takenSlots+"] message from node#"+
                    msg.getSenderId()+". Now he owns "+t+": "
                    +Arrays.toString(takenArray));       
        
        if(msg.getSenderId() == this.nodeId) {
            this.pendingTake = false;
        }
    }
    
    private void handleGiveAway(SlotsMessageGiveAway msg) {
        int[] indexes = msg.getIndexes();
        int counter = 0;
        for(int i = 0; i< GIVE_AWAY; i++) {
            if (indexes[i] != -1) {
                counter++;
            }
        }        
        
        this.println("Received GiveAway["+counter+"] message from Node#"+msg.getSenderId()+": "
                +Arrays.toString(indexes));
        
        for(int i = 0; i < indexes.length; i++) {
            this.slotsTable[i].setStatus(Slot.STATUS_UNCLAIMED);
            this.slotsTable[i].setOwner(Slot.NO_OWNER);
        }
        
        if(msg.getSenderId() == this.nodeId) {
            this.pendingGiveAway = false;
        }
    }    
    
    
    /*
    Check if we need to request or donate slots.
    */
    private void slotsManagement() {
        // check if I need to take slots
        if (this.getFreeSlots() < 1) {
            if(! this.pendingTake) {
                this.broadcastTakeSlots();
            } else {
                //this.println("I have zero slots but broadcasted TAKE already. State: "
                //+ this.getStateAsString());
            }
        }
        
        // check if I have too many slots and need to give away
        if (this.getFreeSlots() >= FREE_HIGH) {
            if(! this.pendingGiveAway) {
                int[] slotsIndexes  = this.getGiveAwaySlots();
                this.broadcastGiveAwaySlots(slotsIndexes);
            } else {
                this.println("I have many slots but broadcasted GiveAwat already. State: "
                + this.getStateAsString());
            }
        }
    }
    
    private void broadcastGiveAwaySlots(int[] slotsIndexes) {
        int counter = 0;
        for(int i = 0; i< GIVE_AWAY; i++) {
            if (slotsIndexes[i] != -1) {
                counter++;
            }
        }
        this.println("Broadcasting Give Away["+counter+"] Message: "+ Arrays.toString(slotsIndexes));
        SlotsMessageGiveAway msg = new SlotsMessageGiveAway(slotsIndexes, this.nodeId);
        this.pendingGiveAway = true;
        this.broadcast(msg);
    }    
    
    /*
    get list of slot indexes I will give away
    */
    private int[] getGiveAwaySlots() {
        int indexes[] = new int[GIVE_AWAY];
        for(int i = 0; i < GIVE_AWAY; i++) {
            indexes[i] = this.getFirstOwnedFreeSlotIndex();
            this.slotsTable[indexes[i]].setStatus(Slot.STATUS_DONATING);
        }
        return indexes;
    }
    
    private void broadcastTakeSlots() {
        this.println("Broadcasting Take Message for "+FREE_LOW+ " slots");
        SlotsMessageTakeSlots msg = new SlotsMessageTakeSlots(FREE_LOW,this.nodeId);
        this.pendingTake = true;
        this.broadcast(msg);
    }


    @Override
    public String getText() {
        return "Node #" + nodeId + "\nStatus: "+ this.getStateAsString()
                +"\nI own: "+ this.getOwnedSlots() + " ("+this.getFreeSlots()+
                " free) slots"+ "\nRegistered Nodes: "
                + Arrays.toString(this.activeNodes)+"\nInitialized Nodes: "
                + Arrays.toString(this.initializedNodes)+"\nDonor Nodes: "
                + Arrays.toString(this.donorsNodes)+"\nForks Succeded: "+this.counterForksSucceded
                +"\nForks Failed: "+this.counterForksFailed
                +"\nExits: "+this.counterExits+"\nConnects: "+this.counterConnects
                +"\nDisconnects: "+this.counterDisconnects
                +"\nRequested Slots: "+this.counterRequestedSlots
                +"\nArrival Median: "+this.arrivalMedian
                +"\nNext Fork: "+this.timeLeftToFork
                +"\nCurrent Time: "+getTime();
    }

    public void println(String str) {
        String st;
        if(this.pendingGiveAway && this.pendingTake) {
            st = "T-GA";
        } else if (this.pendingGiveAway) {
            st = "GA";
        } else if (this.pendingTake) {
            st = "T";
        } else {
            st = "R";
        }        
        System.out.println("Node[" + nodeId + "][" + st + "]("+getTime()+"): "+str);
    }

    public void decProcessesLifetimes() {
   //     this.println("Checking possible ending processes...");
        for(int i = 0; i < SlotsDonation.TOTAL_SLOTS; i++) {
            if(slotsTable[i].isUsed() && slotsTable[i].getOwner() == this.nodeId) {
 //               this.println("SLOT: "+i+" "+slotsTable[i].asString());
                int delta = getTime() - this.lastTimeRegistered;
 //               println("Delta: "+delta);
                slotsTable[i].processTimeLeft = slotsTable[i].processTimeLeft - delta;                
                if(slotsTable[i].processTimeLeft <= 0) {
                    this.exitProcess(i);
                }
            }
        }
        this.lastTimeRegistered = getTime();
    }
    
    private void createProcess(int slotIndex) {
        if (slotsTable[slotIndex].getOwner() != this.nodeId ){
            this.println("[ERROR] I don't own this slot!");
            return;
        }
        if (slotsTable[slotIndex].getStatus() != Slot.STATUS_FREE ){
            this.println("[ERROR] This slot is not free!");
            return;
        } 
        int newProcessLifetime = this.getRandomProcessLifeTime();
        this.slotsTable[slotIndex].setProcessLifetime(newProcessLifetime);
        this.slotsTable[slotIndex].setStatus(Slot.STATUS_USED);
        this.println("Created a process["+slotIndex+"] of lifetime "+newProcessLifetime);
    }

    private boolean isInitialized(int nodeId) {
        return this.initializedNodes[nodeId];
    }

    public boolean isInitialized() {
        return this.isInitialized(this.nodeId);
    }

    private String getStateAsString() {
        switch(this.state) {
            case STS_DISCONNECTED:
                return "Disconnected";
            case STS_ACTIVE:
                return "Active";
            case STS_RUNNING:
                return "Running";
            case STS_WAIT_STATUS:
                return "Wait Status";
            case STS_WAIT_INIT:
                return "Wait Init";
            case STS_REQ_SLOTS:
                return "Requested Slots";
            case STS_NEW:
                return "New ??";
            case STS_MERGE_STATUS:
                return "Merge ??";
            default:
                return "Unknown Status: "+this.state;
        }
    }

    public int getFreeSlots() {
        int counter = 0;
        for(int i = 0; i<SlotsDonation.TOTAL_SLOTS; i++) {
            if(slotsTable[i].isFree() && slotsTable[i].getOwner() == this.nodeId) {
                counter++;
            }
        }
        return counter;
    }

    public int getUsedSlots() {
        int counter = 0;
        for(int i = 0; i<SlotsDonation.TOTAL_SLOTS; i++) {
            if(slotsTable[i].isUsed() && slotsTable[i].getOwner() == this.nodeId) {
                counter++;
            }
        }
        return counter;
    }

    public int getOwnedSlots() {
        int counter = 0;
        for(int i = 0; i<SlotsDonation.TOTAL_SLOTS; i++) {
            if(slotsTable[i].getOwner() == this.nodeId) {
                counter++;
            }
        }
        return counter;
    }

    private void processForkExit() {
        // if we are connected and init we can try stuff
        if(!this.isConnected() || !this.isInitialized()) {
            this.println("[ERROR] I'm not connected or not init");
        } else {
            // time to change lambda?
            if(getTime() > this.nextLambdaChange) {
                this.lambdaArrival = this.getLambdaArrivals();
                this.println("LAMBDA changed to: "+this.lambdaArrival);
                this.nextLambdaChange = getTime() + RELAMBDA;
            }
            // check process that need to finish
            this.decProcessesLifetimes();
            
            // time to new process?
            if (this.timeLeftToFork <= getTime()) { // time for a new fork
                this.timeLeftToFork = getTime()+this.getNextDeltaFork();
                this.tryFork();
            }            
        }
    }

    private boolean isConnected() {
        return (this.activeNodes[this.nodeId]);
    }

    private void doConnect() {
        println("Connecting...");
        this.counterConnects++;
        out(0).send(new SpreadMessageJoin(this.nodeId));
    }

    private int getInitializedNodes() {
        int counter = 0;
        for(int i = 1; i <= SlotsDonation.MAX_NODES; i++) {
            if(this.initializedNodes[i]) {
                counter++;
            }
        }
        return counter;
    }

    private void cleanNodesLists() {
        for(int i = 1; i <= SlotsDonation.MAX_NODES; i++) {
            this.initializedNodes[i] = false;
            this.activeNodes[i] = false;
            this.donorsNodes[i] = false;
            this.bmPendingNodes[i] = false;
        }
    }

    public boolean tryFork() {
        // index of the free slot seeked, -1 in case that there are not free slots
        int freeSlotIndex;
        freeSlotIndex = this.getFirstOwnedFreeSlotIndex();
        // there is not any free slot
        if (freeSlotIndex == -1){
            this.println("Failed fork. No free slot :(");
            this.counterForksFailed++;
            return false;
        } else {
            this.createProcess(freeSlotIndex);
            return true;
        }
    }

    private int getFirstFreeHomelessSlotIndex(){
        for (int i = 0; i < SlotsDonation.TOTAL_SLOTS ; i++) {
            if(slotsTable[i].getOwner() == Slot.NO_OWNER){
                return i;
            }
        }
        return -1;
    }    

    private int getFirstOwnedFreeSlotIndex(){
        for (int i = 0; i < SlotsDonation.TOTAL_SLOTS ; i++) {
            if(slotsTable[i].getOwner() == this.nodeId
                    && slotsTable[i].isFree()){
                return i;
            }
        }
        return -1;
    }

    private void exitProcess(int slotIndex){
        this.counterExits++;
        //avoid killing again and again
        this.slotsTable[slotIndex].processTimeLeft = -1;
        this.slotsTable[slotIndex].status = Slot.STATUS_FREE;
        this.println("Terminating process in Slot "+slotIndex);
    }

    public int[] getCounterGotFirstAt() {
        return this.counterGotFirstSlotAt;
    }

    private int getNextDeltaFork() {
        return  (int)(1+Math.round(
                Math.log(1-this.random.nextDouble())/(-this.lambdaArrival)));
    }

    private int getRandomProcessLifeTime() {
        //int lt = MIN_PLIFETIME + this.random.nextInt(MAX_PLIFETIME - MIN_PLIFETIME + 1);

        double val = this.random.nextFloat();


        for (int i = 1 + LT_MIN ; i<LT_MAX; i++) {
            if(val < 1-(1/(double)i)) {
                return (i - 1) * LT_UNIT;
            }
        }

        return (LT_MAX * LT_UNIT);
    }

    /**
     * Returns a uniform random median in the interval [10;90]
     * @return
     */
    private int getNextArrivalMedian() {
        int median = FI_MIN_AVG + this.random.nextInt(FI_MAX_AVG - FI_MIN_AVG + 1);
        return (median);
    }

}