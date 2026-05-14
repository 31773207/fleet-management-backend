package com.bank.pfe1.service;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.bank.pfe1.entity.Manage;
import com.bank.pfe1.repository.ManageRepository;
import com.bank.pfe1.entity.Employee;
import com.bank.pfe1.entity.Maintenance;
import com.bank.pfe1.entity.Mission;
import com.bank.pfe1.entity.MissionStatus;
import com.bank.pfe1.entity.NotificationType;
import com.bank.pfe1.entity.TechnicalCheck;
import com.bank.pfe1.entity.TechnicalCheckStatus;
import com.bank.pfe1.entity.Vehicle;
import com.bank.pfe1.entity.VehicleStatus;
import com.bank.pfe1.repository.EmployeeRepository;
import com.bank.pfe1.repository.MaintenanceRepository;
import com.bank.pfe1.repository.MissionRepository;
import com.bank.pfe1.repository.TechnicalCheckRepository;
import com.bank.pfe1.repository.VehicleRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final AuditLogService auditLogService;
    private final NotificationService notificationService;
    private final EmployeeRepository employeeRepository;
    private final MissionRepository missionRepository;
    private final MaintenanceRepository maintenanceRepository;
    private final ManageRepository manageRepository;
    private final TechnicalCheckRepository technicalCheckRepository;

    private static final int MILEAGE_THRESHOLD = 10000;

    // ── helper: mileage alert ─────────────────────────────────────────────
    private void checkMileageAlert(Vehicle vehicle) {
        if (vehicle.getKilometrage() != null
                && vehicle.getKilometrage() >= MILEAGE_THRESHOLD
                && vehicle.getStatus() != VehicleStatus.IN_REVISION) {

            notificationService.createNotification(
                    "⚠️ Maintenance Required",
                    "Vehicle " + vehicle.getPlateNumber()
                            + " has reached " + vehicle.getKilometrage()
                            + " km and needs a maintenance check.",
                    NotificationType.MAINTENANCE,
                    vehicle.getId(),
                    "/maintenance?vehicleId=" + vehicle.getId()
            );
        }
    }

    // ── helper: auto-create TechnicalCheck from vehicle's expiry date ─────
    private void syncTechnicalCheck(Vehicle vehicle) {
        LocalDate expiry = vehicle.getTechnicalCheckExpiry();
        if (expiry == null) return;

        // Check if a technical check already exists for this vehicle
        List<TechnicalCheck> existing = technicalCheckRepository.findByVehicleId(vehicle.getId());

        if (existing.isEmpty()) {
            // No record yet — create the first one automatically
            TechnicalCheck check = new TechnicalCheck();
            check.setVehicle(vehicle);
            check.setCheckDate(LocalDate.now());
            check.setExpiryDate(expiry);
            check.setCenter("—");
            check.setNotes("Auto-created when vehicle was added to the system.");
            check.setStatus(expiry.isBefore(LocalDate.now())
                    ? TechnicalCheckStatus.EXPIRED
                    : TechnicalCheckStatus.VALID);
            technicalCheckRepository.save(check);
        } else {
            // Record exists — update the most recent one's expiry date
            TechnicalCheck latest = existing.stream()
                    .reduce((a, b) -> a.getId() > b.getId() ? a : b)
                    .get();
            latest.setExpiryDate(expiry);
            latest.setStatus(expiry.isBefore(LocalDate.now())
                    ? TechnicalCheckStatus.EXPIRED
                    : TechnicalCheckStatus.VALID);
            technicalCheckRepository.save(latest);
        }
    }

    // ── CRUD ──────────────────────────────────────────────────────────────

    public List<Vehicle> getAllVehicles() {
        return vehicleRepository.findAll();
    }

    public List<Vehicle> getVehiclesByStatus(VehicleStatus status) {
        return vehicleRepository.findByStatus(status);
    }

    public Vehicle getVehicleById(Long id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Vehicle not found!"));
    }

    public List<Vehicle> getAvailableVehicles() {
        return vehicleRepository.findAvailableVehicles();
    }

    @Transactional
    public Vehicle createVehicle(Vehicle vehicle) {
        if (vehicleRepository.existsByPlateNumber(vehicle.getPlateNumber())) {
            throw new RuntimeException("Plate number already exists!");
        }

        int currentYear = LocalDate.now().getYear();
        if (vehicle.getYear() != null && (currentYear - vehicle.getYear()) >= 10) {
            vehicle.setStatus(VehicleStatus.REFORMED);
        } else {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
        }

        Vehicle saved = vehicleRepository.save(vehicle);
        auditLogService.log("CREATE", "Vehicle", String.valueOf(saved.getId()),
                "Created vehicle: " + saved.getPlateNumber());

        // ── AUTO-CREATE technical check if expiry date was provided ──
        syncTechnicalCheck(saved);

        return saved;
    }

    @Transactional
    public Vehicle updateVehicle(Long id, Vehicle updated) {
        Vehicle vehicle = getVehicleById(id);
        vehicle.setPlateNumber(updated.getPlateNumber());
        vehicle.setModel(updated.getModel());
        vehicle.setYear(updated.getYear());
        vehicle.setKilometrage(updated.getKilometrage());
        vehicle.setFuelType(updated.getFuelType());
        vehicle.setBrand(updated.getBrand());
        vehicle.setVehicleType(updated.getVehicleType());
        vehicle.setTechnicalCheckExpiry(updated.getTechnicalCheckExpiry()); // ← sync field

        int currentYear = LocalDate.now().getYear();
        if (vehicle.getYear() != null && (currentYear - vehicle.getYear()) >= 10) {
            if (vehicle.getStatus() == VehicleStatus.AVAILABLE
                    || vehicle.getStatus() == VehicleStatus.ASSIGNED) {
                vehicle.setStatus(VehicleStatus.REFORMED);
            }
        } else if (vehicle.getStatus() == VehicleStatus.REFORMED) {
            vehicle.setStatus(VehicleStatus.AVAILABLE);
        }

        Vehicle result = vehicleRepository.save(vehicle);
        auditLogService.log("UPDATE", "Vehicle", String.valueOf(id),
                "Updated vehicle: " + vehicle.getPlateNumber());

        // ── SYNC technical check when expiry date is updated ──
        syncTechnicalCheck(result);

        return result;
    }

    // ── Status changes (unchanged) ────────────────────────────────────────

    @Transactional
    public Vehicle updateStatus(Long id, VehicleStatus status) {
        Vehicle vehicle = getVehicleById(id);
        vehicle.setStatus(status);
        Vehicle updated = vehicleRepository.save(vehicle);
        auditLogService.log("UPDATE_STATUS", "Vehicle", String.valueOf(updated.getId()),
                "Status changed to " + status + " for vehicle: " + updated.getPlateNumber());
        return updated;
    }

    @Transactional
    public Vehicle putInMaintenance(Long id) {
        Vehicle vehicle = getVehicleById(id);
        if (vehicle.getStatus() == VehicleStatus.IN_MISSION) {
            throw new RuntimeException("Cannot put vehicle in maintenance while on mission!");
        }
        vehicle.setStatus(VehicleStatus.IN_REVISION);
        Vehicle updated = vehicleRepository.save(vehicle);
        auditLogService.log("MAINTENANCE", "Vehicle", String.valueOf(updated.getId()),
                "Vehicle put in maintenance: " + updated.getPlateNumber());
        notificationService.createNotification(
                "Vehicle in Maintenance",
                "Vehicle " + vehicle.getPlateNumber() + " has been sent to maintenance",
                NotificationType.MAINTENANCE,
                vehicle.getId(),
                "/maintenance"
        );
        return updated;
    }

    @Transactional
    public Vehicle reportBreakdown(Long id) {
        Vehicle vehicle = getVehicleById(id);
        if (vehicle.getStatus() == VehicleStatus.IN_MISSION) {
            throw new RuntimeException("Cannot report breakdown while on mission!");
        }
        vehicle.setStatus(VehicleStatus.BREAKDOWN);
        Vehicle updated = vehicleRepository.save(vehicle);
        auditLogService.log("BREAKDOWN", "Vehicle", String.valueOf(updated.getId()),
                "Breakdown reported for vehicle: " + updated.getPlateNumber());
        notificationService.createNotification(
                "Vehicle Breakdown",
                "Vehicle " + vehicle.getPlateNumber() + " has reported a breakdown",
                NotificationType.VEHICLE_BREAKDOWN,
                vehicle.getId(),
                "/vehicles"
        );
        return updated;
    }

    @Transactional
    public Vehicle markAvailable(Long id) {
        Vehicle vehicle = getVehicleById(id);
        if (vehicle.getStatus() == VehicleStatus.REFORMED) {
            throw new RuntimeException("Cannot make a reformed vehicle available!");
        }
        vehicle.setStatus(VehicleStatus.AVAILABLE);
        Vehicle updated = vehicleRepository.save(vehicle);
        auditLogService.log("AVAILABLE", "Vehicle", String.valueOf(updated.getId()),
                "Vehicle marked available: " + updated.getPlateNumber());
        notificationService.createNotification(
                "Vehicle Available",
                "Vehicle " + vehicle.getPlateNumber() + " is now available for use",
                NotificationType.GENERAL,
                vehicle.getId(),
                "/vehicles"
        );
        return updated;
    }

    @Transactional
    public Vehicle reformVehicle(Long id) {
        Vehicle vehicle = getVehicleById(id);
        if (vehicle.getStatus() == VehicleStatus.IN_MISSION) {
            throw new RuntimeException("Cannot reform vehicle while on mission!");
        }
        vehicle.setStatus(VehicleStatus.REFORMED);
        Vehicle updated = vehicleRepository.save(vehicle);
        auditLogService.log("REFORM", "Vehicle", String.valueOf(updated.getId()),
                "Vehicle reformed: " + updated.getPlateNumber());
        notificationService.createNotification(
                "Vehicle Reformed",
                "Vehicle " + vehicle.getPlateNumber() + " has been marked as reformed",
                NotificationType.GENERAL,
                vehicle.getId(),
                "/vehicles"
        );
        return updated;
    }

    @Transactional
    public void deleteVehicle(Long id) {
        Vehicle vehicle = getVehicleById(id);
        VehicleStatus status = vehicle.getStatus();

        if (status == VehicleStatus.IN_MISSION) {
            throw new RuntimeException("Cannot delete vehicle! It is currently on a mission.");
        }
        if (status == VehicleStatus.ASSIGNED) {
            throw new RuntimeException("Cannot delete vehicle! It is currently assigned to a driver.");
        }

        List<Mission> allMissions = missionRepository.findByVehicleId(id);
        boolean hasActiveMissions = allMissions.stream()
                .anyMatch(m -> m.getStatus() == MissionStatus.PLANNED
                        || m.getStatus() == MissionStatus.IN_PROGRESS);
        if (hasActiveMissions) {
            throw new RuntimeException("Cannot delete vehicle! It has active/pending missions.");
        }

        List<Maintenance> allMaintenances = maintenanceRepository.findByVehicleId(id);
        boolean hasActiveMaintenance = allMaintenances.stream()
                .anyMatch(m -> m.getStatus() != com.bank.pfe1.entity.MaintenanceStatus.COMPLETED);
        if (hasActiveMaintenance) {
            throw new RuntimeException("Cannot delete vehicle! It has active maintenance in progress.");
        }

        List<Manage> manageRecords = manageRepository.findByVehicleIdOrderByAssignedAtDesc(id);
        manageRecords.forEach(m -> m.setVehicle(null));
        manageRepository.saveAll(manageRecords);

        allMissions.forEach(m -> m.setVehicle(null));
        missionRepository.saveAll(allMissions);

        allMaintenances.forEach(m -> m.setVehicle(null));
        maintenanceRepository.saveAll(allMaintenances);

        if (vehicle.getAssignedTo() != null) {
            Employee employee = vehicle.getAssignedTo();
            employee.setCurrentlyAssignedVehicle(null);
            employee.setVehicleAssignedAt(null);
            employeeRepository.save(employee);
            vehicle.setAssignedTo(null);
            vehicle.setAssignedAt(null);
        }

        auditLogService.log("DELETE", "Vehicle", String.valueOf(id), "Deleted vehicle ID: " + id);
        vehicleRepository.deleteById(id);
    }
}
