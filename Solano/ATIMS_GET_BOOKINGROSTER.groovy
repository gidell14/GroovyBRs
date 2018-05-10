/**
 * Created by mgidell on 5/10/2018.
 *
 * No Parameters
 */

import groovy.sql.Sql

def sql
def serverProp = SystemProperty.getValue("interface.atims.server")
def user = SystemProperty.getValue("interface.atims.user")
def pass = SystemProperty.getValue("interface.atims.pass")

try {												//These settings should all be done through cmsuser.properties file
    def server = "jdbc:sqlserver://" + serverProp
    def driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver"

    /*
      def server = "jdbc:sqlserver://172.18.2.137" 	//interface.atims.server
      def user = "eProbation_ro_user"					//interface.atims.user
      def pass = "ju5t_L00king"						//interface.atims.pass
      def driver = "com.microsoft.sqlserver.jdbc.SQLServerDriver"
  */


    sql = Sql.newInstance(server,user,pass,driver);
}
catch(e){
    logger.debug(e)
}

def query = "select * FROM [CJIS_External].[dbo].[eSuiteBookingLog]  order by Incarceration_id"


def prevNumber = 0
def clientCase
sql.eachRow(query){
    def row = it;
    String bookNum = row.BookingNumber
    logger.debug(row.BookingNumber)
    def bnParts = bookNum.toString().tokenize(".")
    if(bnParts.size() > 0) {
        bookNum = bnParts[0]
    }

    if(row.Number != prevNumber){ //Find new Client... not needed if same "number"
        logger.debug("parse Name: " + row.Name)
        def firstName,lastName,middleName
        def names = row.Name.split(", ")
        lastName = names[0]
        logger.debug("lastName: " + lastName)
        if(names.size()>1){
            firstName =  names[1].split(" ")[0]
            logger.debug("first Name " + firstName)

            if(names[1].split(" ").size() > 1){
                logger.debug("has middle Name")
                middleName = names[1].split(" ")[1]
            }

        }
        logger.debug("searching for " + row.Name)
        logger.debug("firstName:" + firstName)
        logger.debug("lastName:" + lastName)

        prevNumber = row.Number

        //find or create Case record stuff
        logger.debug("calling GET_CLIENT_BY_NAME")
        params = ['firstName':firstName,'lastName':lastName,'dob':row.DOB.format("MM/dd/yyyy")]
        person = runRule('FIND_OR_CREATE_PERSON',params).getValue('person')


        if(person.personClientCase){
            logger.debug('found client')
            clientCase = person.personClientCase

            //todo: need to work with Name,DOB,SSN,SEX,Race,
        }
        else{
            logger.debug('need to create client')
            params = ['person':person]
            clientCase = runRule('CREATE_CLIENT_CASE',params).getValue('case')

            //todo: need to work with SSN,SEX,Race,
        }
    }
    else{
        //This is the same client as last Time!
    }

    //clientCase should be filled in by this point
    logger.debug(clientCase)

    //does this arrest exist?
    withTx(propogation:'REQUIRES_NEW') {
        logger.debug("look for arrest with booking Number: " + bookNum)
        def arrests = clientCase.collect("parties.arrests[bookingNumber==#p1]", bookNum)
        if (arrests.size() > 0) {
            logger.debug("udpate arrest")
            //The arrest exists... update it?

            arrest = arrests.first();
            //maybe check all the fields



            //does this charge exist?   statuteSectionNumber = row.Section
            chgs = arrest.collect("arrestCharges[statuteSectionNumber==#p1]",row.Section)
            if(chgs.size() == 0){
                logger.debug("Adding charge to existing arrest")
                ArrestCharge chg = GetChargeEntity(row);
                arrest.add(chg);
            }
            //did housing change?

        } else {
            //New Arrest
            logger.debug("adding arrest");
            Arrest newArrest = new Arrest();

            newArrest.scp_incarcerationId = row.Incarceration_id
            newArrest.scp_inmateActive = row.inmate_active
            newArrest.scp_facility = row.Facility
            //newArrest.scp_inmateID= row.Number.toDouble() //todo: can we convert this to double?
            newArrest.scp_dateIn = row.DateIn
            newArrest.scp_dateReleased = row.ReleaseOut
            newArrest.scp_elapsed = row.Elapsed
            newArrest.scp_facilityIn = row.FacilityIn
            newArrest.scp_facilityOut = row.FacilityOut
            newArrest.bookingDate = row.BookingDate
            newArrest.scp_clearDate = row.ClearDate
            newArrest.scp_releaseReason = row.ReleaseReason
            newArrest.bookingNumber = bookNum

            newArrest.dirLocation = GetAgency(row.ArrAgency)
            newArrest.arrestType = row.ArrestType
            newArrest.arrestAddress = row.LOA
            newArrest.scp_count = row.Count.toInteger()
            newArrest.scp_status = row.Status
            newArrest.scp_qual = row.Qual
            newArrest.scp_chargeOrderDate = row.ChargeOrder
            newArrest.screeningStatus = "UNSCREEN"

            logger.debug("Find Statute")
            //look for existing statute
            stats = Statute.findBy("Statute","sectionNumber",row.Section)
            Statute statute = null
            if(stats.size() > 0){
                statute = stats.first();
                logger.debug("found statute: " + statute)
            }


            //todo: what if statute doesn't exist? do we add statutes from here, or just do a manual charge on charge entity?

            ArrestCharge charge = GetChargeEntity(row)
            newArrest.add(charge)

            party = clientCase.clientParty


            //todo: need to work with Bail

            logger.debug("work with warrants")
            if(row.WarrantNumber){
                def warrants = party.collect("warrants[trackingNumber==#p1]",row.WarrantNumber)
                if(warrants.size() > 0){
                    //todo: update warrants
                }
                else{
                    //todo: create warrants
                    Warrant w = new Warrant()
                    w.trackingNumber = row.WarrantNumber
                    w.warrantType = row.WarrantType
                    w.agencyName = row.WarrantJurisd
                    w.memo = row.WarrantDesc

                    party.add(w)
                }
            }


            party.add(newArrest)
            party.saveOrUpdateAndExecuteEntityRules(false, false)
            newArrest.saveOrUpdateAndExecuteEntityRules(false, false)
        }
    }


}

logger.debug("done looping")

DirLocation GetAgency(String agency){
  logger.debug("Looking for law agency: " + agency )

  def locs = DirLocation.findByCode(agency)
  if(locs.size() > 0){
    logger.debug("found agency: " + locs.first())
    return locs.first();
  }
  else {
    logger.debug("No agency found")
  }

}

Statute GetStatuteEntity(row){
    logger.debug("Find Statute: " + row.Section)
    //look for existing statute
    stats = Statute.findBy("Statute","sectionNumber",row.Section,"status","ACTIVE")
    Statute statute = null
    if(stats.size() > 0){
        statute = stats.first();
        logger.debug("found statute: " + statute)
    }
    else{
      logger.debug("statute not found")
    }

    return statute;
}

ArrestCharge GetChargeEntity(row){

    Statute statute = GetStatuteEntity(row)

    ArrestCharge charge = new ArrestCharge()
    logger.debug("ArrestCharge created")
    charge.chargeCount = row.Count.toInteger()
    charge.chargeLevel = row.Charge //todo: need to map values
    charge.chargeDegree = row.Charge //todo: need to map values
    if(statute){
        charge.statute = statute
        charge.statuteSectionNumber = row.Section
      //todo: what if some parts don't match?
    }
    else{
        logger.debug("manual statute data")
        charge.statuteSectionNumber = row.Section
        charge.statuteText = row.Description
        charge.scp_sectionCodeType = row.Statute //todo: add to fv
    }

    return charge
}

/*

[Incarceration_id]
      ,[inmate_active]
*      ,[Name]
*      ,[DOB]
*      ,[SSN]
      ,[Facility]
*      ,[Sex]
*      ,[Race]
      ,[Number]
      ,[DateIn]
      ,[ReleaseOut]
      ,[Elapsed]
      ,[FacilityIn]
      ,[FacilityOut]
      ,[BookingDate]
      ,[ClearDate]
      ,[ReleaseReason]
      ,[BookingNumber]
      ,[ArrAgency]
      ,[ArrestType]
      ,[BailTotal]
      ,[LOA]
x*      ,[WarrantNumber]
x*      ,[WarrantType]
x*      ,[WarrantJurisd]
x*      ,[WarrantDesc]
      ,[Count]
*      ,[Bail]
x*      ,[Charge]
x*      ,[Section]
x*      ,[Description]
x*      ,[Statute]
      ,[Status]
      ,[Qual]
      ,[ChargeOrder]



*/



