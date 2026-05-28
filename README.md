# Fleet Management System - Backend

This repository contains the backend of the Fleet Management System developed for the Bank of Algeria.
It provides a RESTful API to manage fleet operations including vehicles, drivers, missions, maintenance, and users.

---

## 🚀 Project Overview

The backend is responsible for handling all business logic and data processing.
It communicates with the frontend application through secure REST APIs.

---

## ⚙️ Main Features

* User authentication and authorization
* Role-based access control (Admin, Fleet Manager, User)
* Vehicle management (CRUD operations)
* Driver management
* Mission and trip tracking
* Maintenance scheduling
* Fuel management system
* Reporting and statistics support

---

## 🔐 Security

* JWT (JSON Web Token) authentication
* Spring Security for access control
* Password encryption using BCrypt
* Role-Based Access Control (RBAC)
* Protected REST endpoints

---

## 🧰 Technologies Used

* Java
* Spring Boot
* Spring Security
* Spring Data JPA (Hibernate)
* JWT
* PostgreSQL
* Maven

---

## 🔗 Related Repositories

* Frontend: https://github.com/31773207/front.git
* Report: https://github.com/your-username/report-repo

---

## ▶️ How to Run

1. Clone the repository

```bash id="runb1"
git clone https://github.com/your-username/backend-repo.git
```

2. Configure database in `application.properties`

3. Run the application:

```bash id="runb2"
mvn spring-boot:run
```

---

## 📌 API Base URL

```
http://localhost:8080/api
```

